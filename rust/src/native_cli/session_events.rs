/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

use crate::router::{RegistrationState, RegistrationToken, RoutedEvent};
use crate::types::{SessionEvent, SessionId};
use crate::{Client, WeakClientInner};
use std::sync::Arc;
use tokio::sync::mpsc;

/// An observe-only registration for a session opened through the generated RPC tree.
///
/// The native CLI's protocol consumers retain their own event handlers. They use
/// the generated session lifecycle methods rather than the convenience session
/// builders. This registration uses the SDK's existing
/// notification router, including its startup buffering and generation ownership.
///
/// Drop removes only this registration. It does not close the runtime session.
/// The consumer must drain promptly and provide its own bounded output queue.
pub struct SessionEvents {
    client: WeakClientInner,
    session_id: SessionId,
    token: RegistrationToken,
    notifications: Notifications,
}

enum Notifications {
    Registered {
        state: Arc<RegistrationState>,
        receiver: mpsc::UnboundedReceiver<RoutedEvent>,
    },
    Observed(mpsc::Receiver<serde_json::Value>),
}

/// Subscribe before opening a session with its caller-assigned ID.
///
/// This API is repository-private host plumbing, not a second session lifecycle.
/// The caller still owns session creation, resume, and close. It must not
/// register SDK callback handlers for this observe-only registration.
pub fn subscribe_session(client: &Client, session_id: impl Into<SessionId>) -> SessionEvents {
    let session_id = session_id.into();
    let registration = client.register_session(&session_id);
    // An observe-only owner has no JSON-RPC callbacks. The router answers an
    // unexpected callback instead of leaving the runtime waiting for a response.
    drop(registration.channels.requests);
    SessionEvents {
        client: WeakClientInner::downgrade(&client.inner),
        session_id,
        token: registration.token,
        notifications: Notifications::Registered {
            state: registration.state,
            receiver: registration.channels.notifications,
        },
    }
}

/// Observe exact event envelopes without replacing another session subscriber.
///
/// The queue never blocks the SDK dispatcher. Overflow closes this observer.
/// Other observers and the session's callback registration stay independent.
pub fn subscribe_session_bounded(
    client: &Client,
    session_id: impl Into<SessionId>,
    capacity: std::num::NonZeroUsize,
) -> SessionEvents {
    let session_id = session_id.into();
    client.inner.router.ensure_started(&client.inner);
    let (token, receiver) = client.inner.router.observe(&session_id, capacity.get());
    SessionEvents {
        client: WeakClientInner::downgrade(&client.inner),
        session_id,
        token,
        notifications: Notifications::Observed(receiver),
    }
}

impl SessionEvents {
    /// Receive the next routed runtime event, or `None` after disconnection.
    ///
    /// A subscription created after connection shutdown ends immediately.
    /// Cancellation leaves the next event in the SDK queue.
    pub async fn recv(&mut self) -> Option<SessionEvent> {
        match &mut self.notifications {
            Notifications::Registered { receiver, .. } => receiver
                .recv()
                .await
                .map(|routed| routed.notification.event),
            Notifications::Observed(receiver) => {
                let raw = receiver.recv().await?;
                match serde_json::from_value(raw) {
                    Ok(event) => Some(event),
                    Err(error) => {
                        tracing::warn!(%error, "could not decode an observed session event");
                        None
                    }
                }
            }
        }
    }

    /// Receive an event without discarding unknown envelope fields.
    ///
    /// Native protocol forwarders use the bounded observer to preserve raw
    /// extension fields. The ordinary SDK registration retains its typed shape.
    pub async fn recv_raw(&mut self) -> Option<serde_json::Value> {
        match &mut self.notifications {
            Notifications::Observed(receiver) => receiver.recv().await,
            Notifications::Registered { receiver, .. } => {
                let event = receiver.recv().await?.notification.event;
                serde_json::to_value(event).ok()
            }
        }
    }
}

impl Drop for SessionEvents {
    fn drop(&mut self) {
        if let Notifications::Registered { state, .. } = &self.notifications {
            state.cancel();
        }
        if let Some(inner) = self.client.upgrade() {
            match &self.notifications {
                Notifications::Registered { .. } => {
                    Client::from_inner(inner)
                        .unregister_session_owned(&self.session_id, self.token);
                }
                Notifications::Observed(_) => {
                    inner.router.unobserve(&self.session_id, self.token);
                }
            }
        }
    }
}
