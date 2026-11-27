//! Repository-private construction for the native CLI, not a published SDK mode.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Weak};

use parking_lot::Mutex;
use serde_json::Value;
use tokio::sync::mpsc;
use tracing::Instrument;

use crate::{
    Client, ClientOptions, Error, ErrorKind, InProcessProtocolConnection, RuntimeConnection,
};

/// A runtime notification from the CLI's existing SDK connection.
pub struct Notification {
    /// The exact wire method.
    pub method: String,
    /// The wire parameters, or null when the frame omits them.
    pub params: Value,
}

/// An ordered stream that releases its registration when dropped.
pub struct NotificationSubscription {
    receiver: mpsc::UnboundedReceiver<Notification>,
    registry: Weak<NotificationSubscriptions>,
    id: u64,
}

impl NotificationSubscription {
    /// Receive the next matching frame, or None after connection closure.
    ///
    /// Buffered frames drain before closure. Dropping this future does not
    /// consume a frame or remove the subscription.
    pub async fn next(&mut self) -> Option<Notification> {
        self.receiver.recv().await
    }
}

impl Drop for NotificationSubscription {
    fn drop(&mut self) {
        if let Some(registry) = self.registry.upgrade() {
            registry.state.lock().subscribers.remove(&self.id);
        }
    }
}

struct Subscriber {
    methods: HashSet<String>,
    sender: mpsc::UnboundedSender<Notification>,
}

#[derive(Default)]
struct SubscriptionState {
    next_id: u64,
    closed: bool,
    subscribers: HashMap<u64, Subscriber>,
}

#[derive(Default)]
pub(crate) struct NotificationSubscriptions {
    state: Mutex<SubscriptionState>,
}

impl NotificationSubscriptions {
    pub(crate) fn subscribe(
        self: &Arc<Self>,
        methods: &[&str],
    ) -> Result<NotificationSubscription, Error> {
        let mut state = self.state.lock();
        if state.closed {
            return Err(Error::with_message(
                ErrorKind::InvalidConfig,
                "Notification subscriptions require an open connection",
            ));
        }
        let id = state.next_id;
        state.next_id += 1;
        let (sender, receiver) = mpsc::unbounded_channel();
        state.subscribers.insert(
            id,
            Subscriber {
                methods: methods.iter().map(|method| (*method).to_owned()).collect(),
                sender,
            },
        );
        Ok(NotificationSubscription {
            receiver,
            registry: Arc::downgrade(self),
            id,
        })
    }

    pub(crate) fn dispatch(&self, notification: &crate::jsonrpc::JsonRpcNotification) {
        for subscriber in self.state.lock().subscribers.values() {
            if subscriber.methods.contains(&notification.method) {
                let _ = subscriber.sender.send(Notification {
                    method: notification.method.clone(),
                    params: notification.params.clone().unwrap_or(Value::Null),
                });
            }
        }
    }

    pub(crate) fn close(&self) {
        let mut state = self.state.lock();
        state.closed = true;
        state.subscribers.clear();
    }
}

/// Subscribe to exact wire methods in frame order without retaining the client.
///
/// Each subscription has its own unbounded queue. The read loop only enqueues
/// frames; it never runs subscriber work. A closed connection rejects registration.
///
/// ```no_run
/// # async fn example(client: &copilot_sdk::Client) -> Result<(), copilot_sdk::Error> {
/// use copilot_sdk::native_cli::subscribe_notifications;
/// let mut notifications = subscribe_notifications(
///     client,
///     &["remoteHosts.stateChanged", "remoteHosts.sessionChanged"],
/// )?;
/// while let Some(notification) = notifications.next().await {
///     println!("{}: {}", notification.method, notification.params);
/// }
/// # Ok(())
/// # }
/// ```
pub fn subscribe_notifications(
    client: &Client,
    methods: &[&str],
) -> Result<NotificationSubscription, Error> {
    client.inner.rpc.subscribe_notifications(methods)
}

mod session_events;
pub use session_events::{SessionEvents, subscribe_session, subscribe_session_bounded};

/// Immutable inputs for an OTel attachment to the native CLI's original engine.
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OtelHostConfiguration<'a> {
    pub service_version: &'a str,
    pub local_telemetry: Option<&'a serde_json::Value>,
    pub environment: &'a [(String, String)],
}

/// Policy operations on the original engine's native telemetry owner.
#[derive(serde::Deserialize, serde::Serialize)]
#[serde(
    tag = "operation",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum OtelSessionAdmission {
    Prepare {
        resume: bool,
        params: serde_json::Value,
    },
    Bind {
        preparation_id: Option<String>,
        session_id: String,
        traceparent: Option<String>,
        tracestate: Option<String>,
        owned: bool,
    },
    Commit {
        preparation_id: String,
    },
    Rollback {
        preparation_id: String,
        session_id: Option<String>,
    },
    ReloadLocal {
        settings: Option<serde_json::Value>,
    },
}

/// Prepare, bind, or settle policy without a TypeScript telemetry owner.
///
/// Dropping an unfinished preparation rolls back its reservation, including
/// when the native reply arrives before the SDK caller accepts it.
pub async fn admit_otel_session(
    client: &Client,
    admission: &OtelSessionAdmission,
) -> Result<serde_json::Value, Error> {
    let mut params = serde_json::to_value(admission)
        .map_err(|error| Error::with_message(ErrorKind::InvalidConfig, error.to_string()))?;
    let method = match admission {
        OtelSessionAdmission::Prepare { .. } => "host.telemetry.prepare",
        OtelSessionAdmission::Bind { .. } => "host.telemetry.bind",
        OtelSessionAdmission::Commit { .. } => "host.telemetry.commit",
        OtelSessionAdmission::Rollback { .. } => "host.telemetry.rollback",
        OtelSessionAdmission::ReloadLocal { .. } => "host.telemetry.reloadLocal",
    };
    params
        .as_object_mut()
        .expect("an admission is an object")
        .remove("operation");
    if matches!(admission, OtelSessionAdmission::Prepare { .. }) {
        let mut delivery = PreparationDelivery {
            client: Arc::downgrade(&client.inner),
            state: Arc::new(Mutex::new(PreparationDeliveryState::Waiting)),
            accepted: false,
        };
        let state = delivery.state.clone();
        let owner = delivery.client.clone();
        let result = client
            .call_with_inline_callback(
                method,
                Some(params),
                Some(Box::new(move |response| {
                    if let Some(id) = response
                        .result
                        .as_ref()
                        .and_then(|result| result.get("preparationId"))
                        .and_then(Value::as_str)
                    {
                        let abandoned = {
                            let mut state = state.lock();
                            if matches!(*state, PreparationDeliveryState::Abandoned) {
                                true
                            } else {
                                *state = PreparationDeliveryState::Prepared(id.to_owned());
                                false
                            }
                        };
                        if abandoned {
                            PreparationDelivery::rollback(&owner, id.to_owned());
                        }
                    }
                    Ok(())
                })),
            )
            .await;
        delivery.accepted = result.is_ok();
        return result;
    }
    client.call(method, Some(params)).await
}

enum PreparationDeliveryState {
    Waiting,
    Prepared(String),
    Abandoned,
}

struct PreparationDelivery {
    client: Weak<crate::ClientInner>,
    state: Arc<Mutex<PreparationDeliveryState>>,
    accepted: bool,
}

impl PreparationDelivery {
    fn rollback(owner: &Weak<crate::ClientInner>, preparation_id: String) {
        let Some(inner) = owner.upgrade() else {
            // Connection teardown rolls back reservations when no client remains.
            return;
        };
        let request = Client::from_inner(inner).begin_call(
            "host.telemetry.rollback",
            Some(serde_json::json!({ "preparationId": preparation_id })),
        );
        match request {
            Ok(request) => {
                tokio::spawn(async move {
                    if let Err(error) = request.await {
                        tracing::warn!(%error, "Failed to roll back abandoned telemetry preparation");
                    }
                });
            }
            Err(error) => {
                tracing::warn!(%error, "Failed to submit abandoned telemetry preparation rollback");
            }
        }
    }
}

impl Drop for PreparationDelivery {
    fn drop(&mut self) {
        if !self.accepted {
            let previous =
                std::mem::replace(&mut *self.state.lock(), PreparationDeliveryState::Abandoned);
            if let PreparationDeliveryState::Prepared(id) = previous {
                Self::rollback(&self.client, id);
            }
        }
    }
}

/// Attach native OTel policy and event tracking without starting another engine.
pub async fn initialize_otel(
    client: &Client,
    configuration: &OtelHostConfiguration<'_>,
) -> Result<bool, Error> {
    let configuration = serde_json::to_value(configuration)
        .map_err(|error| Error::with_message(ErrorKind::InvalidConfig, error.to_string()))?;
    client
        .call("host.telemetry.initialize", Some(configuration))
        .await?
        .as_bool()
        .ok_or_else(|| {
            Error::with_message(ErrorKind::InvalidConfig, "Invalid OTel attachment result")
        })
}

/// Join final OTel export before the original SDK connection closes.
pub async fn shutdown_otel(client: &Client) -> Result<(), Error> {
    client.call("host.telemetry.shutdown", None).await.map(drop)
}

/// Install an owner-scoped timing consumer during the native CLI's connection setup.
///
/// The consumer performs only in-memory initialization; release admission and endpoint
/// resolution remain asynchronous. Ordinary SDK builds do not compile this entry point.
pub fn initialize_session_timing(
    client: &Client,
    config: &serde_json::Value,
) -> Result<bool, Error> {
    let _span = tracing::debug_span!("session.timing.cli_timing_initialize").entered();
    let config = serde_json::to_vec(config)
        .map_err(|error| Error::with_message(ErrorKind::InvalidConfig, error.to_string()))?;
    let host = client.inner.ffi_host.lock();
    let host = host.as_ref().ok_or_else(|| {
        Error::with_message(
            ErrorKind::InvalidConfig,
            "CLI timing initialization requires an active in-process connection",
        )
    })?;
    host.initialize_session_timing(&config)
}

/// Start the native CLI's connection after normal SDK setup.
///
/// The caller checks image identity before the SDK captures CLI authority.
/// Ordinary SDK builds do not compile this entry point.
pub async fn start<E>(
    options: ClientOptions,
    validate_image: impl FnOnce(&Client) -> Result<(), E>,
) -> Result<Client, E>
where
    E: From<Error>,
{
    start_for_client(options, None, validate_image).await
}

/// Declare the native launch surface before an owned runtime creates its telemetry sinks.
pub async fn start_for_client<E>(
    options: ClientOptions,
    client_type: Option<&str>,
    validate_image: impl FnOnce(&Client) -> Result<(), E>,
) -> Result<Client, E>
where
    E: From<Error>,
{
    if !matches!(
        &options.runtime,
        Some(RuntimeConnection::InProcess(_) | RuntimeConnection::AttachedInProcess(_))
    ) {
        return Err(Error::with_message(
            ErrorKind::InvalidConfig,
            "native CLI construction requires the in-process transport",
        )
        .into());
    }
    let integration_id = std::env::var("GITHUB_COPILOT_INTEGRATION_ID")
        .ok()
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "copilot-developer-cli".to_owned());
    // Keep SDK startup out of the native host's nested initialization frames.
    let client = Box::pin(Client::start_internal(
        options,
        Some(integration_id),
        client_type,
    ))
    .await
    .map_err(E::from)?;
    finish_start(client, validate_image)
}

/// Open the original CLI stdio stream with its live process principal.
///
/// Attached clients and ordinary SDK peers cannot use this constructor.
/// The returned stream owns only its connection, not its engine or CLI host.
///
/// ```no_run
/// # async fn example(client: &copilot_sdk::Client) -> Result<(), copilot_sdk::Error> {
/// let stdio = copilot_sdk::native_cli::open_stdio_connection(client).await?;
/// stdio.close().await?;
/// # Ok(())
/// # }
/// ```
pub async fn open_stdio_connection(client: &Client) -> Result<InProcessProtocolConnection, Error> {
    let host = client.inner.ffi_host.lock().clone().ok_or_else(|| {
        Error::with_message(
            ErrorKind::InvalidConfig,
            "original stdio requires an active in-process CLI client",
        )
    })?;
    crate::ffi::open_original_stdio_connection(host)
        .instrument(tracing::debug_span!("session.timing.cli_stdio_admission"))
        .await
}

fn finish_start<E>(
    client: Client,
    validate_image: impl FnOnce(&Client) -> Result<(), E>,
) -> Result<Client, E>
where
    E: From<Error>,
{
    let _span = tracing::debug_span!("session.timing.cli_host_admission").entered();
    let admission = validate_image(&client).and_then(|()| {
        let host = client.inner.ffi_host.lock();
        let host = host.as_ref().ok_or_else(|| {
            E::from(Error::with_message(
                ErrorKind::InvalidConfig,
                "native CLI construction requires an active in-process connection",
            ))
        })?;
        host.admit_cli_host().map_err(E::from)
    });
    if let Err(error) = admission {
        client.force_stop();
        return Err(error);
    }
    Ok(client)
}

#[cfg(test)]
mod tests;
