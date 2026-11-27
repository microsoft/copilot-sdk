#![cfg(test)]

use std::net::SocketAddr;
use std::panic::AssertUnwindSafe;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;

use copilot_sdk::handler::ApproveAllHandler;
use copilot_sdk::rpc::{
    HostAgentRegistryPrepareSpawnResult, HostAgentRegistrySpawnPrepared,
    HostAgentRegistrySpawnPreparedKind, HostAgentRegistrySpawnRequest,
    HostCallbacksRegisterRequest, HostForegroundSessionRequest, HostTransportKind,
    HostTransportProtocol, HostTransportRequest, HostTransportStartRequest,
    SessionsCheckInUseRequest,
};
use copilot_sdk::{
    Client, ClientOptions, CreateSessionOptions, InProcessRuntimeOptions, ResumeSessionOptions,
    RuntimeConnection, RuntimeHostConfiguration,
};
use futures_util::FutureExt;
use serde_json::{Value, json};
use tokio::sync::{Semaphore, mpsc};

use super::support::{
    DEFAULT_TEST_TOKEN, E2eContext, default_event_timeout, with_dedicated_e2e_context_no_snapshot,
};

struct Host {
    owner: Client,
    peer: Client,
    transport_id: String,
}

impl Host {
    async fn start(ctx: &E2eContext, spawn_enabled: bool) -> Self {
        ctx.set_default_copilot_user();
        let owner = Client::start_with_host_configuration(
            ctx.client_options_with_transport(RuntimeConnection::InProcess(
                InProcessRuntimeOptions::new(),
            ))
            .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            RuntimeHostConfiguration {
                feature_flags: Some(json!({ "SESSIONS_SIDEBAR_TAB": spawn_enabled })),
                ..Default::default()
            },
        )
        .await
        .unwrap();
        let listener = owner
            .rpc()
            .host()
            .transports()
            .start(HostTransportStartRequest {
                protocol: HostTransportProtocol::JsonRpc,
                transport: HostTransportKind::Tcp,
                ..Default::default()
            })
            .await
            .unwrap();
        let address: SocketAddr = listener.address.unwrap().parse().unwrap();
        let peer = Client::start(
            ClientOptions::new(copilot_sdk::ClientMode::CodingAgent).with_runtime(
                RuntimeConnection::Tcp(copilot_sdk::TcpRuntimeOptions {
                    host: address.ip().to_string(),
                    port: address.port(),
                    connection_token: None,
                }),
            ),
        )
        .await
        .unwrap();
        Self {
            owner,
            peer,
            transport_id: listener.transport_id,
        }
    }

    async fn stop(self) {
        let peer = self.peer.stop().await;
        let transport = self
            .owner
            .rpc()
            .host()
            .transports()
            .stop(HostTransportRequest {
                transport_id: self.transport_id,
            })
            .await;
        let owner = self.owner.stop().await;
        assert!(
            peer.is_ok() && transport.is_ok() && owner.is_ok(),
            "host teardown failed: peer={peer:?}, transport={transport:?}, owner={owner:?}"
        );
    }
}

fn session_config(ctx: &E2eContext) -> CreateSessionOptions {
    CreateSessionOptions::default()
        .with_model("gpt-4.1")
        .with_working_directory(ctx.work_dir())
        .with_enable_config_discovery(false)
        .with_github(|group| group.with_fetch_managed_settings(false))
        .with_permissions(|group| group.with_on_request(Arc::new(ApproveAllHandler)))
}

fn foreground_registration() -> HostCallbacksRegisterRequest {
    HostCallbacksRegisterRequest {
        foreground_session_change: Some(true),
        ..Default::default()
    }
}

async fn wait_for_spawn_observation(
    client: &Client,
    session_id: &copilot_sdk::SessionId,
    cursor: &Value,
    request_id: &str,
    disposition: Option<&str>,
) -> Result<Value, String> {
    let mut cursor = cursor.clone();
    let mut last = Value::Null;
    tokio::time::timeout(default_event_timeout(), async {
        loop {
            last = client
                .call(
                    "session.diagnostics.lifecycle.read",
                    Some(json!({
                        "sessionId": session_id,
                        "cursor": cursor,
                        "max": 500,
                        "waitMs": 1000,
                    })),
                )
                .await
                .map_err(|error| error.to_string())?;
            if last["cursorStatus"] != "ok" {
                return Err(format!("spawn observation cursor expired: {last}"));
            }
            if last["events"].as_array().is_some_and(|events| {
                events.iter().any(|event| {
                    let data = &event["data"];
                    data["system"] == "runtime-host-spawn"
                        && data["changedOperation"]["operationId"] == request_id
                        && data["changedOperation"]["disposition"].as_str() == disposition
                })
            }) {
                return Ok(last.take());
            }
            cursor = last["cursor"].clone();
        }
    })
    .await
    .unwrap_or_else(|_| Err(format!("missing spawn observation {request_id}: {last}")))
}

#[tokio::test]
async fn foreground_callback_rejects_nested_selection_without_blocking_its_reply() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, false).await;
            let session = host
                .owner
                .create_session(session_config(ctx))
                .await
                .unwrap();
            let selected_id = session.id().clone();
            let (results, mut received) = mpsc::unbounded_channel();
            host.owner
                .register_request_handler(
                    "runtimeHost.foregroundSessionChange",
                    move |_, client| {
                        let results = results.clone();
                        let selected_id = selected_id.clone();
                        async move {
                            let nested = client.set_foreground_session_id(&selected_id).await;
                            results
                                .send(nested.map_err(|error| error.to_string()))
                                .unwrap();
                            Ok(json!({ "success": true }))
                        }
                    },
                )
                .unwrap();
            host.owner
                .rpc()
                .host()
                .callbacks()
                .register(foreground_registration())
                .await
                .unwrap();
            let result = tokio::time::timeout(
                default_event_timeout(),
                host.peer.set_foreground_session_id(session.id()),
            )
            .await;
            let nested = received.try_recv();
            let foreground = if result.as_ref().is_ok_and(Result::is_ok) {
                host.owner.get_foreground_session_id().await.unwrap()
            } else {
                None
            };
            host.stop().await;
            result
                .expect("the callback must not deadlock on nested selection")
                .unwrap();
            let error = nested
                .unwrap()
                .expect_err("nested selection must fail before waiting");
            assert!(error.contains("pending foreground callback"), "{error}");
            assert_eq!(foreground.as_ref(), Some(session.id()));
        })
    })
    .await;
}

#[tokio::test]
async fn foreground_callback_preserves_scope_on_created_and_resumed_session_clients() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, false).await;
            let selected = host
                .owner
                .create_session(session_config(ctx))
                .await
                .unwrap();
            let cold = host
                .owner
                .create_session(session_config(ctx).with_name("Callback scope cold resume"))
                .await
                .unwrap();
            let cold_id = cold.id().clone();
            cold.disconnect().await.unwrap();
            let config = session_config(ctx);
            let entered = Arc::new(AtomicBool::new(false));
            let (results, mut received) = mpsc::unbounded_channel();
            host.owner
                .register_request_handler(
                    "runtimeHost.foregroundSessionChange",
                    move |_, client| {
                        let config = config.clone();
                        let cold_id = cold_id.clone();
                        let entered = Arc::clone(&entered);
                        let results = results.clone();
                        async move {
                            if entered.swap(true, Ordering::SeqCst) {
                                return Ok(json!({ "success": true }));
                            }
                            let created = client.create_session(config).await?;
                            let resumed = client
                                .resume_session(
                                    ResumeSessionOptions::new(cold_id).with_permissions(|group| {
                                        group.with_on_request(Arc::new(ApproveAllHandler))
                                    }),
                                )
                                .await?;
                            let mut checks = Vec::new();
                            let mut accepted: Vec<
                                futures_util::future::BoxFuture<
                                    'static,
                                    copilot_sdk::Result<Value>,
                                >,
                            > = Vec::new();
                            for (kind, session) in [("created", &created), ("resumed", &resumed)] {
                                match session.client().begin_call(
                                    "session.setForeground",
                                    Some(json!({ "sessionId": session.id() })),
                                ) {
                                    Err(error) => {
                                        checks.push((kind, error.to_string()));
                                        let error = session
                                            .client()
                                            .set_foreground_session_id(session.id())
                                            .await
                                            .expect_err(
                                                "the typed helper must retain callback scope",
                                            );
                                        assert!(
                                            error
                                                .to_string()
                                                .contains("pending foreground callback"),
                                            "{kind}: {error}"
                                        );
                                    }
                                    Ok(pending) => {
                                        checks.push((
                                            kind,
                                            "nested request was submitted".to_owned(),
                                        ));
                                        accepted.push(Box::pin(pending));
                                    }
                                }
                            }
                            assert!(results.send((checks, [created, resumed], accepted)).is_ok());
                            Ok(json!({ "success": true }))
                        }
                    },
                )
                .unwrap();
            host.owner
                .rpc()
                .host()
                .callbacks()
                .register(foreground_registration())
                .await
                .unwrap();
            let body = AssertUnwindSafe(tokio::time::timeout(default_event_timeout(), async {
                host.peer
                    .set_foreground_session_id(selected.id())
                    .await
                    .unwrap();
                let (checks, sessions, accepted) = received.recv().await.unwrap();
                // Drain escaped submissions after the outer callback releases its foreground lease.
                for pending in accepted {
                    let _ = pending.await;
                }
                for session in &sessions {
                    session
                        .client()
                        .set_foreground_session_id(session.id())
                        .await
                        .unwrap();
                }
                host.owner
                    .set_foreground_session_id(selected.id())
                    .await
                    .unwrap();
                for (kind, result) in checks {
                    assert!(
                        result.contains("pending foreground callback"),
                        "{kind} session client lost callback scope: {result}"
                    );
                }
            }))
            .catch_unwind()
            .await;
            let stopped = AssertUnwindSafe(host.stop()).catch_unwind().await;
            match body {
                Err(error) => {
                    if stopped.is_err() {
                        eprintln!("Host teardown also failed: {stopped:?}");
                    }
                    std::panic::resume_unwind(error);
                }
                Ok(result) => result.expect("session-derived clients must not deadlock selection"),
            }
            if let Err(error) = stopped {
                std::panic::resume_unwind(error);
            }
        })
    })
    .await;
}

#[tokio::test]
async fn foreground_callback_serializes_independent_selections_from_its_owner() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, false).await;
            let first = host.owner.create_session(session_config(ctx)).await.unwrap();
            let second = host.owner.create_session(session_config(ctx)).await.unwrap();
            let release = Arc::new(Semaphore::new(0));
            let gate = Arc::clone(&release);
            let (requests, mut received) = mpsc::unbounded_channel();
            host.owner
                .register_request_handler("runtimeHost.foregroundSessionChange", move |params, _| {
                    let requests = requests.clone();
                    let gate = Arc::clone(&gate);
                    async move {
                        let request: HostForegroundSessionRequest = serde_json::from_value(params)?;
                        requests.send(request).unwrap();
                        gate.acquire_owned().await.unwrap().forget();
                        Ok(json!({ "success": true }))
                    }
                })
                .unwrap();
            host.owner
                .rpc()
                .host()
                .callbacks()
                .register(foreground_registration())
                .await
                .unwrap();
            let result = AssertUnwindSafe(tokio::time::timeout(default_event_timeout(), async {
                let selecting_first = host.owner.begin_call(
                    "session.setForeground",
                    Some(json!({ "sessionId": first.id() })),
                )?;
                let request = received.recv().await.unwrap();
                assert_eq!(request.session_id.as_ref(), Some(first.id()));
                let mut selecting_second = Box::pin(host.owner.begin_call(
                    "session.setForeground",
                    Some(json!({ "sessionId": second.id() })),
                )?);
                release.add_permits(1);
                assert_eq!(selecting_first.await?["success"], true);
                let request = tokio::select! {
                    request = received.recv() => request.unwrap(),
                    result = &mut selecting_second => panic!("independent selection ended before its callback: {result:?}"),
                };
                assert_eq!(request.session_id.as_ref(), Some(second.id()));
                release.add_permits(1);
                let result = selecting_second.await?;
                assert_eq!(result["success"], true, "{result}");
                host.owner.get_foreground_session_id().await
            }))
            .catch_unwind()
            .await;
            release.add_permits(2);
            let stopped = AssertUnwindSafe(host.stop()).catch_unwind().await;
            let result = match result {
                Ok(result) => result,
                Err(error) => {
                    if stopped.is_err() {
                        eprintln!("Host teardown also failed: {stopped:?}");
                    }
                    std::panic::resume_unwind(error);
                }
            };
            if let Err(error) = stopped {
                std::panic::resume_unwind(error);
            }
            assert_eq!(result.unwrap().unwrap().as_ref(), Some(second.id()));
        })
    })
    .await;
}

#[tokio::test]
async fn foreground_callback_preserves_sdk_lifecycle_and_caller_authority() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, false).await;
            let warm = host
                .owner
                .create_session(session_config(ctx))
                .await
                .unwrap();
            let cold = host
                .owner
                .create_session(session_config(ctx).with_name("Callback cold resume"))
                .await
                .unwrap();
            let cold_id = cold.id().clone();
            cold.disconnect().await.unwrap();
            let owner_connection = host.owner.in_process_connection_id().unwrap();
            let (requests, mut received) = mpsc::unbounded_channel();
            let (resumed, mut resumed_sessions) = mpsc::unbounded_channel();
            let requested_cold = cold_id.clone();
            host.owner
                .register_request_handler("runtimeHost.foregroundSessionChange", move |params, client| {
                    let requests = requests.clone();
                    let resumed = resumed.clone();
                    let cold_id = requested_cold.clone();
                    async move {
                        let request: HostForegroundSessionRequest = serde_json::from_value(params)?;
                        if request.session_id.as_deref() == Some(cold_id.as_str()) {
                            let session = client
                                .resume_session(
                                    ResumeSessionOptions::new(cold_id)
                                        .with_permissions(|group| group.with_on_request(Arc::new(ApproveAllHandler))),
                                )
                                .await?;
                            resumed.send(session).unwrap();
                        }
                        let response = match request.session_id.as_deref() {
                            Some("refused") => {
                                json!({ "success": false, "error": "The controller refused selection" })
                            }
                            Some("refused-without-reason") => json!({ "success": false }),
                            _ => json!({ "success": true }),
                        };
                        requests.send(request).unwrap();
                        Ok(response)
                    }
                })
                .unwrap();
            let refused_registration = host
                .peer
                .rpc()
                .host()
                .callbacks()
                .register(foreground_registration())
                .await
                .unwrap_err();
            assert!(
                refused_registration
                    .to_string()
                    .contains("SDK-owned runtime controller"),
                "{refused_registration}"
            );
            host.owner
                .rpc()
                .host()
                .callbacks()
                .register(foreground_registration())
                .await
                .unwrap();
            assert_eq!(host.peer.get_foreground_session_id().await.unwrap(), None);
            for id in [warm.id(), &cold_id] {
                let result = host
                    .peer
                    .call(
                        "session.setForeground",
                        Some(json!({
                            "sessionId": id,
                            "caller": { "connectionId": owner_connection, "isExtension": true },
                        })),
                    )
                    .await
                    .unwrap_or_else(|error| {
                        let phase = if id == warm.id() { "warm" } else { "cold resume" };
                        panic!(
                            "foreground selection for {phase} session {id} failed: {error}; RPC data: {:?}",
                            error.rpc_data()
                        );
                    });
                assert_eq!(result["success"], true, "{result}");
                let request = received.recv().await.unwrap();
                assert_eq!(request.session_id.as_deref(), Some(id.as_str()));
                assert_ne!(request.caller.connection_id, i64::from(owner_connection));
                assert!(!request.caller.is_extension);
                assert_eq!(
                    host.owner
                        .get_foreground_session_id()
                        .await
                        .unwrap()
                        .as_ref(),
                    Some(id)
                );
            }
            let restored = resumed_sessions.recv().await.unwrap();
            assert_eq!(
                restored.rpc().name().get().await.unwrap().name.as_deref(),
                Some("Callback cold resume")
            );
            for id in ["refused", "refused-without-reason", "not-opened"] {
                let result = host
                    .peer
                    .call("session.setForeground", Some(json!({ "sessionId": id })))
                    .await
                    .unwrap();
                assert_eq!(result["success"], false, "{result}");
                let error = host
                    .peer
                    .set_foreground_session_id(&id.into())
                    .await
                    .expect_err("the public helper must propagate foreground refusal");
                let expected_reason = result["error"]
                    .as_str()
                    .unwrap_or("session.setForeground reported failure");
                assert!(
                    error.to_string().contains(expected_reason),
                    "the public helper lost the refusal reason: {error}"
                );
                assert_eq!(
                    host.peer.get_foreground_session_id().await.unwrap(),
                    Some(cold_id.clone())
                );
            }
            host.peer
                .set_foreground_session_id(warm.id())
                .await
                .unwrap();
            assert_eq!(
                host.peer.get_foreground_session_id().await.unwrap(),
                Some(warm.id().clone())
            );
            host.owner
                .rpc()
                .host()
                .callbacks()
                .register(HostCallbacksRegisterRequest::default())
                .await
                .unwrap();
            assert_eq!(host.peer.get_foreground_session_id().await.unwrap(), None);
            restored.disconnect().await.unwrap();
            warm.disconnect().await.unwrap();
            host.stop().await;
        })
    })
    .await;
}

#[tokio::test]
async fn foreground_callback_restores_selection_after_target_close() {
    for (with_previous, close_previous_during_restore) in
        [(false, false), (true, false), (true, true)]
    {
        let (result_sender, result_receiver) = tokio::sync::oneshot::channel();
        with_dedicated_e2e_context_no_snapshot(move |ctx| {
            Box::pin(async move {
                let host = Host::start(ctx, false).await;
                let body = AssertUnwindSafe(async {
                    let previous = host
                        .owner
                        .create_session(session_config(ctx).with_name("Previous foreground"))
                        .await
                        .unwrap();
                    let target = host
                        .owner
                        .create_session(session_config(ctx))
                        .await
                        .unwrap();
                    let displayed = Arc::new(std::sync::Mutex::new(Value::Null));
                    let callback_displayed = Arc::clone(&displayed);
                    let rejected_id = target.id().clone();
                    let previous_id = previous.id().clone();
                    let rejected = Arc::new(AtomicBool::new(false));
                    let (resumed, mut resumed_sessions) = mpsc::unbounded_channel();
                    host.owner
                        .register_request_handler(
                            "runtimeHost.foregroundSessionChange",
                            move |params, client| {
                                let displayed = Arc::clone(&callback_displayed);
                                let rejected_id = rejected_id.clone();
                                let previous_id = previous_id.clone();
                                let rejected = Arc::clone(&rejected);
                                let resumed = resumed.clone();
                                async move {
                                    let requested = params["sessionId"].clone();
                                    if requested == json!(rejected_id) {
                                        client
                                            .call(
                                                "sessions.close",
                                                Some(json!({ "sessionId": rejected_id })),
                                            )
                                            .await?;
                                        rejected.store(true, Ordering::SeqCst);
                                    } else if close_previous_during_restore
                                        && requested == json!(previous_id)
                                        && rejected.swap(false, Ordering::SeqCst)
                                    {
                                        client
                                            .call(
                                                "sessions.close",
                                                Some(json!({ "sessionId": previous_id })),
                                            )
                                            .await?;
                                        let session = client
                                            .resume_session(
                                                ResumeSessionOptions::new(previous_id.clone())
                                                    .with_enable_config_discovery(false)
                                                    .with_github(|group| {
                                                        group.with_fetch_managed_settings(false)
                                                    })
                                                    .with_permissions(|group| {
                                                        group.with_on_request(Arc::new(
                                                            ApproveAllHandler,
                                                        ))
                                                    }),
                                            )
                                            .await?;
                                        assert!(resumed.send(session).is_ok());
                                    }
                                    *displayed.lock().unwrap() = requested;
                                    Ok(json!({ "success": true }))
                                }
                            },
                        )
                        .unwrap();
                    host.owner
                        .rpc()
                        .host()
                        .callbacks()
                        .register(foreground_registration())
                        .await
                        .unwrap();
                    let expected = if with_previous {
                        host.owner
                            .set_foreground_session_id(previous.id())
                            .await
                            .unwrap();
                        (!close_previous_during_restore).then(|| previous.id().clone())
                    } else {
                        None
                    };
                    host.peer
                        .set_foreground_session_id(target.id())
                        .await
                        .expect_err("the callback closed the target before final admission");
                    assert_eq!(
                        host.owner.get_foreground_session_id().await.unwrap(),
                        expected,
                        "final admission must preserve the canonical foreground"
                    );
                    assert_eq!(
                        *displayed.lock().unwrap(),
                        json!(expected),
                        "the controller kept the rejected foreground selection"
                    );
                    host.owner
                        .rpc()
                        .host()
                        .callbacks()
                        .register(HostCallbacksRegisterRequest::default())
                        .await
                        .unwrap();
                    while let Ok(session) = resumed_sessions.try_recv() {
                        session.disconnect().await.unwrap();
                    }
                    target.disconnect().await.unwrap();
                    previous.disconnect().await.unwrap();
                })
                .catch_unwind()
                .await;
                let stopped = AssertUnwindSafe(host.stop()).catch_unwind().await;
                assert!(result_sender.send((body, stopped)).is_ok());
            })
        })
        .await;
        let (body, stopped) = result_receiver.await.unwrap();
        if let Err(error) = body {
            if stopped.is_err() {
                eprintln!("Host teardown also failed: {stopped:?}");
            }
            std::panic::resume_unwind(error);
        }
        if let Err(error) = stopped {
            std::panic::resume_unwind(error);
        }
    }
}

#[tokio::test]
async fn foreground_restoration_survives_requester_teardown() {
    foreground_restoration_after_requester_teardown(false).await;
}

#[tokio::test]
async fn foreground_selection_survives_requester_teardown_before_acknowledgment() {
    foreground_restoration_after_requester_teardown(true).await;
}

async fn foreground_restoration_after_requester_teardown(before_acknowledgment: bool) {
    struct SelectionReceipt {
        complete: bool,
        settled: mpsc::UnboundedSender<bool>,
    }

    impl Drop for SelectionReceipt {
        fn drop(&mut self) {
            let _ = self.settled.send(self.complete);
        }
    }

    with_dedicated_e2e_context_no_snapshot(move |ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, false).await;
            let target = host.owner.create_session(session_config(ctx)).await.unwrap();
            let rejected_id = target.id().clone();
            let displayed = Arc::new(std::sync::Mutex::new(Value::Null));
            let callback_displayed = Arc::clone(&displayed);
            let release = Arc::new(Semaphore::new(0));
            let callback_release = Arc::clone(&release);
            let (entered, mut restoring) = mpsc::unbounded_channel();
            let (settled, mut completed) = mpsc::unbounded_channel();
            host.owner
                .register_request_handler("runtimeHost.foregroundSessionChange", move |params, client| {
                    let rejected_id = rejected_id.clone();
                    let displayed = Arc::clone(&callback_displayed);
                    let release = Arc::clone(&callback_release);
                    let entered = entered.clone();
                    let settled = settled.clone();
                    async move {
                        let requested = params["sessionId"].clone();
                        if requested.is_null() {
                            let mut receipt = SelectionReceipt { complete: false, settled };
                            if !before_acknowledgment {
                                entered.send(()).unwrap();
                                release.acquire().await.unwrap().forget();
                            }
                            *displayed.lock().unwrap() = requested;
                            receipt.complete = true;
                        } else {
                            *displayed.lock().unwrap() = requested.clone();
                            if requested == json!(rejected_id) {
                                client.call("sessions.close", Some(json!({ "sessionId": rejected_id }))).await?;
                                if before_acknowledgment {
                                    entered.send(()).unwrap();
                                    release.acquire().await.unwrap().forget();
                                }
                            }
                        }
                        Ok(json!({ "success": true }))
                    }
                })
                .unwrap();
            host.owner.rpc().host().callbacks().register(foreground_registration()).await.unwrap();
            let requesting_peer = host.peer.clone();
            let request = requesting_peer.set_foreground_session_id(target.id());
            tokio::pin!(request);
            tokio::select! {
                entered = restoring.recv() => entered.unwrap(),
                result = &mut request => panic!("foreground request ended before restoration: {result:?}"),
            }
            host.peer.stop().await.unwrap();
            host.owner.rpc().host().transports().stop(HostTransportRequest {
                transport_id: host.transport_id.clone(),
            }).await.unwrap();
            assert!(request.await.is_err());
            release.add_permits(1);
            assert_eq!(
                tokio::time::timeout(default_event_timeout(), completed.recv()).await.unwrap(),
                Some(true),
                "requester teardown abandoned the foreground restoration"
            );
            assert_eq!(*displayed.lock().unwrap(), Value::Null);
            assert_eq!(host.owner.get_foreground_session_id().await.unwrap(), None);
            let next = host.owner.create_session(session_config(ctx)).await.unwrap();
            host.owner.set_foreground_session_id(next.id()).await.unwrap();
            assert_eq!(*displayed.lock().unwrap(), json!(next.id()));
            host.owner.rpc().host().callbacks().register(HostCallbacksRegisterRequest::default()).await.unwrap();
            next.disconnect().await.unwrap();
            target.disconnect().await.unwrap();
            host.owner.stop().await.unwrap();
        })
    }).await;
}

#[tokio::test]
async fn foreground_release_reaps_detached_sessions_without_losing_other_owners_or_history() {
    let (result_sender, result_receiver) = tokio::sync::oneshot::channel();
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, false).await;
            let observer = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::SpawnedStdio(
                    Default::default(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            )
            .await
            .unwrap();
            let body = AssertUnwindSafe(async {
                let shared = host
                    .owner
                    .create_session(session_config(ctx).with_name("Other owner"))
                    .await
                    .unwrap();
                let retained = host
                    .peer
                    .resume_session(
                        ResumeSessionOptions::new(shared.id().clone()).with_permissions(|group| {
                            group.with_on_request(Arc::new(ApproveAllHandler))
                        }),
                    )
                    .await
                    .unwrap();
                let first = host
                    .owner
                    .create_session(session_config(ctx).with_name("Released by selection"))
                    .await
                    .unwrap();
                let last = host
                    .owner
                    .create_session(session_config(ctx).with_name("Released by unregistration"))
                    .await
                    .unwrap();
                let first_id = first.id().clone();
                let last_id = last.id().clone();
                let owned_ids = vec![
                    shared.id().to_string(),
                    first_id.to_string(),
                    last_id.to_string(),
                ];
                assert_eq!(
                    observer
                        .rpc()
                        .sessions()
                        .check_in_use(SessionsCheckInUseRequest {
                            session_ids: owned_ids.clone(),
                        })
                        .await
                        .unwrap()
                        .in_use,
                    owned_ids,
                    "the independent process must observe the initial storage claims"
                );
                host.owner
                    .register_request_handler("runtimeHost.foregroundSessionChange", |_, _| async {
                        Ok(json!({ "success": true }))
                    })
                    .unwrap();
                host.owner
                    .rpc()
                    .host()
                    .callbacks()
                    .register(foreground_registration())
                    .await
                    .unwrap();
                for (target, session) in [("shared", shared), ("first", first), ("last", last)] {
                    let result = host
                        .peer
                        .call(
                            "session.setForeground",
                            Some(json!({ "sessionId": session.id() })),
                        )
                        .await
                        .unwrap_or_else(|error| {
                            panic!(
                                "foreground selection for {target} session {} failed: {error}; RPC data: {:?}",
                                session.id(),
                                error.rpc_data()
                            );
                        });
                    assert_eq!(result["success"], true, "{result}");
                    session.disconnect().await.unwrap();
                }
                host.owner
                    .rpc()
                    .host()
                    .callbacks()
                    .register(HostCallbacksRegisterRequest::default())
                    .await
                    .unwrap();
                let released_ids = vec![first_id.to_string(), last_id.to_string()];
                let mut remaining = released_ids.clone();
                let cleanup = tokio::time::timeout(default_event_timeout(), async {
                    loop {
                        remaining = observer
                            .rpc()
                            .sessions()
                            .check_in_use(SessionsCheckInUseRequest {
                                session_ids: released_ids.clone(),
                            })
                            .await
                            .unwrap()
                            .in_use;
                        if remaining.is_empty() {
                            break;
                        }
                        tokio::time::sleep(Duration::from_millis(100)).await;
                    }
                })
                .await;
                assert!(
                    cleanup.is_ok(),
                    "foreground release retained ownerless storage claims: {remaining:?}"
                );
                assert_eq!(
                    observer
                        .rpc()
                        .sessions()
                        .check_in_use(SessionsCheckInUseRequest {
                            session_ids: vec![retained.id().to_string()],
                        })
                        .await
                        .unwrap()
                        .in_use,
                    vec![retained.id().to_string()],
                    "another SDK owner's session must remain live"
                );
                assert_eq!(
                    retained.rpc().name().get().await.unwrap().name.as_deref(),
                    Some("Other owner")
                );
                for (id, name) in [
                    (first_id, "Released by selection"),
                    (last_id, "Released by unregistration"),
                ] {
                    let resumed = host
                        .owner
                        .resume_session(ResumeSessionOptions::new(id).with_permissions(|group| {
                            group.with_on_request(Arc::new(ApproveAllHandler))
                        }))
                        .await
                        .unwrap();
                    assert_eq!(
                        resumed.rpc().name().get().await.unwrap().name.as_deref(),
                        Some(name)
                    );
                    resumed.disconnect().await.unwrap();
                }
                retained.disconnect().await.unwrap();
            })
            .catch_unwind()
            .await;
            let observer = observer.stop().await;
            let host = AssertUnwindSafe(host.stop()).catch_unwind().await;
            assert!(result_sender.send((body, observer, host)).is_ok());
        })
    })
    .await;
    let (body, observer, host) = result_receiver.await.unwrap();
    if let Err(error) = body {
        if observer.is_err() || host.is_err() {
            eprintln!("Additional teardown failure: observer={observer:?}, host={host:?}");
        }
        std::panic::resume_unwind(error);
    }
    observer.unwrap();
    if let Err(error) = host {
        std::panic::resume_unwind(error);
    }
}

#[tokio::test]
async fn foreground_selection_refuses_busy_creation_without_waiting_for_its_callback() {
    use copilot_sdk::{
        GitHubToken, GitHubTokenProvider, GitHubTokenProviderArgs, GitHubTokenProviderResult,
        SessionId,
    };

    struct HeldToken {
        entered: tokio::sync::Notify,
        release: tokio::sync::watch::Sender<bool>,
    }

    #[async_trait::async_trait]
    impl GitHubTokenProvider for HeldToken {
        async fn get_token(
            &self,
            _args: GitHubTokenProviderArgs,
        ) -> copilot_sdk::Result<GitHubTokenProviderResult> {
            let mut released = self.release.subscribe();
            self.entered.notify_one();
            released.wait_for(|released| *released).await.unwrap();
            Ok(GitHubTokenProviderResult::Token(GitHubToken::new(
                DEFAULT_TEST_TOKEN,
                8 * 60 * 60,
            )))
        }
    }

    let (result_sender, result_receiver) = tokio::sync::oneshot::channel();
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, false).await;
            let body = AssertUnwindSafe(async {
                let previous = host
                    .owner
                    .create_session(session_config(ctx))
                    .await
                    .unwrap();
                host.owner
                    .register_request_handler("runtimeHost.foregroundSessionChange", |_, _| async {
                        Ok(json!({ "success": true }))
                    })
                    .unwrap();
                host.owner
                    .rpc()
                    .host()
                    .callbacks()
                    .register(foreground_registration())
                    .await
                    .unwrap();
                host.owner
                    .set_foreground_session_id(previous.id())
                    .await
                    .unwrap();
                let target_id = SessionId::new(uuid::Uuid::new_v4().to_string());
                let provider = Arc::new(HeldToken {
                    entered: tokio::sync::Notify::new(),
                    release: tokio::sync::watch::channel(false).0,
                });
                let creating = host.peer.create_session(
                    session_config(ctx)
                        .with_session_id(target_id.clone())
                        .with_github(|group| group.with_token_provider(provider.clone())),
                );
                tokio::pin!(creating);
                tokio::select! {
                    () = provider.entered.notified() => {}
                    result = &mut creating => panic!(
                        "creation ended before its SDK callback: {:?}",
                        result.as_ref().map(|session| session.id()),
                    ),
                }
                let selection = tokio::time::timeout(
                    default_event_timeout(),
                    host.owner.set_foreground_session_id(&target_id),
                )
                .await;
                provider.release.send_replace(true);
                let target = creating.await.unwrap();
                let error = selection
                    .expect("foreground selection waited for the pending SDK callback")
                    .expect_err("a busy target must refuse foreground selection");
                assert!(
                    error.to_string().contains("foreground session is busy"),
                    "{error}"
                );
                assert_eq!(
                    host.owner.get_foreground_session_id().await.unwrap(),
                    Some(previous.id().clone()),
                    "refusal must preserve the previous foreground session"
                );
                host.owner
                    .set_foreground_session_id(target.id())
                    .await
                    .unwrap();
                assert_eq!(
                    host.owner.get_foreground_session_id().await.unwrap(),
                    Some(target_id),
                    "the completed target must become selectable"
                );
                host.owner
                    .rpc()
                    .host()
                    .callbacks()
                    .register(HostCallbacksRegisterRequest::default())
                    .await
                    .unwrap();
                target.disconnect().await.unwrap();
                previous.disconnect().await.unwrap();
            })
            .catch_unwind()
            .await;
            let stopped = AssertUnwindSafe(host.stop()).catch_unwind().await;
            assert!(result_sender.send((body, stopped)).is_ok());
        })
    })
    .await;
    let (body, stopped) = result_receiver.await.unwrap();
    if let Err(error) = body {
        if stopped.is_err() {
            eprintln!("Host teardown also failed: {stopped:?}");
        }
        std::panic::resume_unwind(error);
    }
    if let Err(error) = stopped {
        std::panic::resume_unwind(error);
    }
}

#[tokio::test]
async fn foreground_selection_refuses_public_close_during_its_native_shutdown_hook() {
    assert_shutdown_admission("sessions.close").await;
}

#[tokio::test]
async fn foreground_selection_refuses_direct_shutdown_during_its_native_hook() {
    assert_shutdown_admission("session.shutdown").await;
}

async fn assert_shutdown_admission(shutdown_method: &'static str) {
    use std::sync::atomic::{AtomicUsize, Ordering};

    use copilot_sdk::hooks::{HookContext, SessionEndInput, SessionEndOutput, SessionHooks};
    use copilot_sdk::rpc::SessionLifecycleMode;

    struct HeldEnd {
        entered: tokio::sync::Notify,
        release: tokio::sync::watch::Sender<bool>,
    }

    #[async_trait::async_trait]
    impl SessionHooks for HeldEnd {
        async fn on_session_end(
            &self,
            _input: SessionEndInput,
            _context: HookContext,
        ) -> Option<SessionEndOutput> {
            let mut released = self.release.subscribe();
            self.entered.notify_one();
            released.wait_for(|released| *released).await.unwrap();
            None
        }
    }

    let (result_sender, result_receiver) = tokio::sync::oneshot::channel();
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, true).await;
            let hook = Arc::new(HeldEnd {
                entered: tokio::sync::Notify::new(),
                release: tokio::sync::watch::channel(false).0,
            });
            let body = AssertUnwindSafe(async {
                let previous = host
                    .owner
                    .create_session(session_config(ctx))
                    .await
                    .unwrap();
                let target = host
                    .owner
                    .create_session(
                        session_config(ctx)
                            .with_name("Native close foreground admission")
                            .with_session_lifecycle_mode(SessionLifecycleMode::Interactive)
                            .with_hooks(|group| group.with_handlers(hook.clone())),
                    )
                    .await
                    .unwrap();
                host.owner
                    .register_request_handler("runtimeHost.foregroundSessionChange", |_, _| async {
                        Ok(json!({ "success": true }))
                    })
                    .unwrap();
                let launches = Arc::new(AtomicUsize::new(0));
                let callback_launches = Arc::clone(&launches);
                host.owner
                    .register_request_handler(
                        "runtimeHost.agentRegistryPrepareSpawn",
                        |_, _| async { Ok(json!({ "kind": "prepared" })) },
                    )
                    .unwrap();
                host.owner
                    .register_request_handler("runtimeHost.agentRegistrySpawn", move |_, _| {
                        let launches = Arc::clone(&callback_launches);
                        async move {
                            launches.fetch_add(1, Ordering::SeqCst);
                            Ok(json!({
                                "kind": "spawn-error",
                                "code": "HOST_REFUSED",
                                "message": "The test host refused this launch",
                            }))
                        }
                    })
                    .unwrap();
                host.owner
                    .rpc()
                    .host()
                    .callbacks()
                    .register(HostCallbacksRegisterRequest {
                        foreground_session_change: Some(true),
                        agent_registry_spawn: Some(true),
                        controller_session_id: Some(target.id().to_string()),
                    })
                    .await
                    .unwrap();
                host.owner
                    .call(
                        "session.permissions.setMode",
                        Some(json!({ "sessionId": target.id(), "mode": "allow-all" })),
                    )
                    .await
                    .unwrap();
                let launch = || {
                    json!({
                        "cwd": ctx.work_dir().to_string_lossy(),
                        "permissionMode": "yolo",
                    })
                };
                let initial_launch = host
                    .peer
                    .call("agentRegistry.spawn", Some(launch()))
                    .await
                    .unwrap();
                assert_eq!(initial_launch["code"], "HOST_REFUSED", "{initial_launch}");
                assert_eq!(launches.load(Ordering::SeqCst), 1);
                host.owner
                    .set_foreground_session_id(previous.id())
                    .await
                    .unwrap();
                let closing = host
                    .peer
                    .call(shutdown_method, Some(json!({ "sessionId": target.id() })));
                tokio::pin!(closing);
                tokio::select! {
                    () = hook.entered.notified() => {}
                    result = &mut closing => panic!(
                        "{shutdown_method} finished before its native shutdown hook: {result:?}"
                    ),
                }
                let selection = tokio::time::timeout(
                    default_event_timeout(),
                    host.owner.set_foreground_session_id(target.id()),
                )
                .await;
                let launch_after_shutdown =
                    host.peer.call("agentRegistry.spawn", Some(launch())).await;
                let during_close = host.owner.get_foreground_session_id().await;
                hook.release.send_replace(true);
                let closed = closing.await;
                let error = selection
                    .expect("foreground selection waited for native teardown")
                    .expect_err("shutdown must refuse a new foreground selection");
                assert!(
                    error.to_string().contains("foreground session is busy"),
                    "{error}"
                );
                let error = launch_after_shutdown
                    .expect_err("shutdown must refuse a new controller launch");
                assert!(
                    error
                        .to_string()
                        .contains("spawn controller session has retired"),
                    "{error}"
                );
                assert_eq!(
                    launches.load(Ordering::SeqCst),
                    1,
                    "a new launch reached the host after controller shutdown started"
                );
                assert_eq!(
                    during_close.unwrap(),
                    Some(previous.id().clone()),
                    "native teardown must not publish the closing target"
                );
                closed.unwrap();
                assert_eq!(
                    host.owner.get_foreground_session_id().await.unwrap(),
                    Some(previous.id().clone()),
                    "native close must preserve the prior foreground selection"
                );
                host.owner
                    .rpc()
                    .host()
                    .callbacks()
                    .register(HostCallbacksRegisterRequest::default())
                    .await
                    .unwrap();
                target.disconnect().await.unwrap();
                previous.disconnect().await.unwrap();
            })
            .catch_unwind()
            .await;
            hook.release.send_replace(true);
            let stopped = AssertUnwindSafe(host.stop()).catch_unwind().await;
            assert!(result_sender.send((body, stopped)).is_ok());
        })
    })
    .await;
    let (body, stopped) = result_receiver.await.unwrap();
    if let Err(error) = body {
        if stopped.is_err() {
            eprintln!("Host teardown also failed: {stopped:?}");
        }
        std::panic::resume_unwind(error);
    }
    if let Err(error) = stopped {
        std::panic::resume_unwind(error);
    }
}

struct CallbackDropped(mpsc::UnboundedSender<()>);

impl Drop for CallbackDropped {
    fn drop(&mut self) {
        let _ = self.0.send(());
    }
}

#[tokio::test]
async fn foreground_callback_replacement_and_peer_close_reconcile_the_dispatched_selection() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, false).await;
            let (entered, mut started) = mpsc::unbounded_channel();
            let (restored, mut cleared) = mpsc::unbounded_channel();
            let release = Arc::new(Semaphore::new(0));
            let callback_release = release.clone();
            let displayed = Arc::new(std::sync::Mutex::new(Value::Null));
            let callback_displayed = displayed.clone();
            host.owner
                .register_request_handler("runtimeHost.foregroundSessionChange", move |params, client| {
                    let entered = entered.clone();
                    let restored = restored.clone();
                    let release = callback_release.clone();
                    let displayed = callback_displayed.clone();
                    async move {
                        let requested = params["sessionId"].clone();
                        *displayed.lock().unwrap() = requested.clone();
                        if requested.is_null() {
                            restored.send(()).unwrap();
                        } else {
                            client.call("sessions.close", Some(json!({ "sessionId": requested }))).await?;
                            entered.send(()).unwrap();
                            release.acquire().await.unwrap().forget();
                        }
                        Ok(json!({ "success": true }))
                    }
                })
                .unwrap();
            host.owner
                .rpc()
                .host()
                .callbacks()
                .register(foreground_registration())
                .await
                .unwrap();
            for disconnect in [false, true] {
                let session = host.owner.create_session(session_config(ctx)).await.unwrap();
                let request = host.peer.call(
                    "session.setForeground",
                    Some(json!({ "sessionId": session.id() })),
                );
                tokio::pin!(request);
                tokio::select! {
                    entered = started.recv() => entered.unwrap(),
                    result = &mut request => panic!("foreground request ended before its callback: {result:?}"),
                }
                if disconnect {
                    host.peer.stop().await.unwrap();
                } else {
                    host.owner.rpc().host().callbacks().register(foreground_registration()).await.unwrap();
                }
                release.add_permits(1);
                let result = request.await;
                if disconnect {
                    assert!(result.is_err(), "the disconnected requester received a result: {result:?}");
                } else {
                    assert_eq!(result.unwrap()["success"], false, "the closed target became foreground");
                }
                tokio::time::timeout(default_event_timeout(), cleared.recv())
                    .await
                    .expect("the dispatched selection was not reconciled")
                    .unwrap();
                assert_eq!(host.owner.get_foreground_session_id().await.unwrap(), None);
                assert_eq!(*displayed.lock().unwrap(), Value::Null);
                session.disconnect().await.unwrap();
            }
            host.stop().await;
        })
    })
    .await;
}

#[tokio::test]
async fn accepted_spawn_finishes_after_the_requesting_peer_disconnects() {
    accepted_spawn_finishes_after_owner_change(true).await;
}

#[tokio::test]
async fn accepted_spawn_finishes_after_callback_registration_replacement() {
    accepted_spawn_finishes_after_owner_change(false).await;
}

async fn accepted_spawn_finishes_after_owner_change(disconnect: bool) {
    struct LaunchReceipt {
        outcome: mpsc::UnboundedSender<bool>,
        completed: bool,
    }

    impl Drop for LaunchReceipt {
        fn drop(&mut self) {
            let _ = self.outcome.send(self.completed);
        }
    }

    with_dedicated_e2e_context_no_snapshot(move |ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, true).await;
            let controller = host.owner.create_session(session_config(ctx)).await.unwrap();
            let (entered, mut started) = mpsc::unbounded_channel();
            let (outcome, mut settled) = mpsc::unbounded_channel();
            let release = Arc::new(Semaphore::new(0));
            host.owner.register_request_handler("runtimeHost.agentRegistryPrepareSpawn", |_, _| async {
                Ok(json!({ "kind": "prepared" }))
            }).unwrap();
            host.owner.register_request_handler("runtimeHost.agentRegistrySpawn", {
                let release = release.clone();
                move |_, _| {
                    let entered = entered.clone();
                    let release = release.clone();
                    let receipt = LaunchReceipt { outcome: outcome.clone(), completed: false };
                    async move {
                        let mut receipt = receipt;
                        entered.send(()).unwrap();
                        let _ready = release.acquire().await.unwrap();
                        receipt.completed = true;
                        Ok(json!({ "kind": "spawn-error", "message": "Readiness settled after launch" }))
                    }
                }
            }).unwrap();
            host.owner.rpc().host().callbacks().register(HostCallbacksRegisterRequest {
                agent_registry_spawn: Some(true),
                controller_session_id: Some(controller.id().to_string()),
                ..Default::default()
            }).await.unwrap();
            let request = host.peer.call("agentRegistry.spawn", Some(json!({ "cwd": ctx.work_dir() })));
            tokio::pin!(request);
            tokio::select! {
                started = started.recv() => started.unwrap(),
                result = &mut request => panic!("spawn ended before launch: {result:?}"),
            }
            if disconnect {
                host.peer.stop().await.unwrap();
            } else {
                host.owner.rpc().host().callbacks().register(HostCallbacksRegisterRequest {
                    agent_registry_spawn: Some(true),
                    controller_session_id: Some(controller.id().to_string()),
                    ..Default::default()
                }).await.unwrap();
            }
            release.add_permits(1);
            let result = request.await;
            let completed = tokio::time::timeout(default_event_timeout(), settled.recv()).await;
            host.owner.rpc().host().transports().stop(HostTransportRequest {
                transport_id: host.transport_id.clone(),
            }).await.unwrap();
            controller.disconnect().await.unwrap();
            host.owner.stop().await.unwrap();
            if disconnect {
                assert!(result.is_err());
            } else {
                assert_eq!(result.unwrap()["message"], "Readiness settled after launch");
            }
            assert_eq!(completed.unwrap(), Some(true), "requester teardown abandoned the accepted launch");
        })
    }).await;
}

#[tokio::test]
async fn spawn_registration_retirement_cancels_preparation_without_launch() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, true).await;
            let controller = host
                .owner
                .create_session(session_config(ctx))
                .await
                .unwrap();
            let (entered, mut started) = mpsc::unbounded_channel();
            let (dropped, mut cancelled) = mpsc::unbounded_channel();
            let launches = Arc::new(AtomicUsize::new(0));
            host.owner
                .register_request_handler("runtimeHost.agentRegistryPrepareSpawn", move |_, _| {
                    let entered = entered.clone();
                    let dropped = dropped.clone();
                    async move {
                        let _owned = CallbackDropped(dropped);
                        entered.send(()).unwrap();
                        std::future::pending::<copilot_sdk::Result<Value>>().await
                    }
                })
                .unwrap();
            host.owner
                .register_request_handler("runtimeHost.agentRegistrySpawn", {
                    let launches = launches.clone();
                    move |_, _| {
                        launches.fetch_add(1, Ordering::SeqCst);
                        async {
                            Ok(json!({ "kind": "spawn-error", "message": "Unexpected launch" }))
                        }
                    }
                })
                .unwrap();
            host.owner
                .rpc()
                .host()
                .callbacks()
                .register(HostCallbacksRegisterRequest {
                    agent_registry_spawn: Some(true),
                    controller_session_id: Some(controller.id().to_string()),
                    ..Default::default()
                })
                .await
                .unwrap();
            {
                let request = host.peer.call(
                    "agentRegistry.spawn",
                    Some(json!({ "cwd": ctx.work_dir() })),
                );
                tokio::pin!(request);
                tokio::select! {
                    entered = started.recv() => entered.unwrap(),
                    result = &mut request => panic!("spawn ended before preparation: {result:?}"),
                }
                host.owner
                    .rpc()
                    .host()
                    .callbacks()
                    .register(HostCallbacksRegisterRequest::default())
                    .await
                    .unwrap();
                assert!(request.await.is_err());
                tokio::time::timeout(default_event_timeout(), cancelled.recv())
                    .await
                    .expect("the SDK preparation survived callback registration retirement")
                    .unwrap();
            }
            assert_eq!(launches.load(Ordering::SeqCst), 0);
            controller.disconnect().await.unwrap();
            host.stop().await;
        })
    })
    .await;
}

#[tokio::test]
async fn controller_spawn_rejects_yolo_revoked_during_host_preparation() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let host = Host::start(ctx, true).await;
            let created = host
                .owner
                .call(
                    "session.create",
                    Some(json!({
                        "model": "gpt-4.1",
                        "workingDirectory": ctx.work_dir(),
                        "enableConfigDiscovery": false,
                        "github": { "fetchManagedSettings": false },
                        "lifecycleDiagnostics": { "expose": true },
                    })),
                )
                .await
                .unwrap();
            let controller = host
                .owner
                .resume_session(
                    ResumeSessionOptions::new(created["sessionId"].as_str().unwrap().to_string().into())
                        .with_permissions(|group| {
                            group.with_on_request(Arc::new(ApproveAllHandler))
                        }),
                )
                .await
                .unwrap();
            let observer = host
                .peer
                .resume_session(
                    ResumeSessionOptions::new(controller.id().clone()).with_permissions(|group| {
                        group.with_on_request(Arc::new(ApproveAllHandler))
                    }),
                )
                .await
                .unwrap();
            let allowed = host
                .owner
                .call(
                    "session.permissions.setMode",
                    Some(json!({ "sessionId": controller.id(), "mode": "allow-all" })),
                )
                .await
                .unwrap();
            assert_eq!(allowed["mode"], "allow-all", "{allowed}");

            let (prepared, mut preparing) = mpsc::unbounded_channel();
            let resume = Arc::new(Semaphore::new(0));
            let launches = Arc::new(AtomicUsize::new(0));
            host.owner
                .register_request_handler("runtimeHost.agentRegistryPrepareSpawn", {
                    let resume = resume.clone();
                    let prepared = prepared.clone();
                    move |params, _| {
                        let prepared = prepared.clone();
                        let resume = resume.clone();
                        async move {
                            let request: HostAgentRegistrySpawnRequest =
                                serde_json::from_value(params)?;
                            assert!(!request.request_id.is_empty());
                            assert_eq!(
                                serde_json::to_value(request.request)?["permissionMode"],
                                "yolo"
                            );
                            prepared.send(Some(request.request_id.to_string())).unwrap();
                            resume.acquire().await.unwrap().forget();
                            Ok(serde_json::to_value(HostAgentRegistryPrepareSpawnResult::Prepared(
                                HostAgentRegistrySpawnPrepared {
                                    kind: HostAgentRegistrySpawnPreparedKind::Prepared,
                                },
                            ))?)
                        }
                    }
                })
                .unwrap();
            host.owner
                .register_request_handler("runtimeHost.agentRegistrySpawn", {
                    let resume = resume.clone();
                    let launches = launches.clone();
                    move |params, _| {
                        let prepared = prepared.clone();
                        let resume = resume.clone();
                        let launches = launches.clone();
                        async move {
                            // Preserve the original reproducer against runtimes without preparation.
                            // The two-phase commit path has no validation wait before its launch.
                            if params.get("requestId").is_none() {
                                assert_eq!(params["request"]["permissionMode"], "yolo");
                                prepared.send(None).unwrap();
                                resume.acquire().await.unwrap().forget();
                            } else {
                                let _: HostAgentRegistrySpawnRequest =
                                    serde_json::from_value(params)?;
                            }
                            launches.fetch_add(1, Ordering::SeqCst);
                            Ok(json!({
                                "kind": "spawn-error",
                                "message": "The controlled launch reached its process owner",
                                "code": "LAUNCH_REACHED",
                            }))
                        }
                    }
                })
                .unwrap();
            host.owner
                .rpc()
                .host()
                .callbacks()
                .register(HostCallbacksRegisterRequest {
                    agent_registry_spawn: Some(true),
                    controller_session_id: Some(controller.id().to_string()),
                    ..Default::default()
                })
                .await
                .unwrap();
            let before = host
                .owner
                .call(
                    "session.diagnostics.lifecycle.read",
                    Some(json!({ "sessionId": controller.id(), "max": 500 })),
                )
                .await
                .unwrap();
            assert_eq!(before["hasMore"], false, "{before}");
            let mut observed = None;
            let response = {
                let spawn = host.peer.call(
                    "agentRegistry.spawn",
                    Some(json!({ "cwd": ctx.work_dir(), "permissionMode": "yolo" })),
                );
                tokio::pin!(spawn);
                let request_id = tokio::select! {
                    entered = preparing.recv() => entered.unwrap(),
                    result = &mut spawn => panic!("spawn ended before host preparation: {result:?}"),
                };
                if let Some(request_id) = request_id {
                    let active = wait_for_spawn_observation(
                        &host.owner,
                        controller.id(),
                        &before["cursor"],
                        &request_id,
                        None,
                    )
                    .await;
                    observed = Some((request_id, active));
                }
                let revoked = host
                    .peer
                    .call(
                        "session.permissions.setMode",
                        Some(json!({ "sessionId": controller.id(), "mode": "manual" })),
                    )
                    .await
                    .unwrap();
                assert_eq!(revoked["mode"], "manual", "{revoked}");
                resume.add_permits(1);
                spawn.await.unwrap()
            };
            let actual_launches = launches.load(Ordering::SeqCst);
            let terminal = if let Some((request_id, Ok(active))) = observed.as_ref() {
                Some(
                    wait_for_spawn_observation(
                        &host.owner,
                        controller.id(),
                        &active["cursor"],
                        request_id,
                        Some("failed"),
                    )
                    .await,
                )
            } else {
                None
            };
            observer.disconnect().await.unwrap();
            controller.disconnect().await.unwrap();
            host.stop().await;
            assert_eq!(
                actual_launches, 0,
                "a host launch crossed a completed permission revocation: {response}"
            );
            assert_eq!(response["reason"], "yolo-not-allowed", "{response}");
            let (request_id, active) = observed.expect("the runtime skipped pure preparation");
            let active = active.unwrap();
            let matching = active["events"]
                .as_array()
                .unwrap()
                .iter()
                .map(|event| &event["data"])
                .filter(|data| {
                    data["system"] == "runtime-host-spawn"
                        && data["changedOperation"]["operationId"] == request_id
                })
                .collect::<Vec<_>>();
            assert!(!matching.is_empty(), "missing preparation operation {request_id}: {active}");
            let incarnation = &matching[0]["incarnationId"];
            assert!(matching.iter().all(|data| &data["incarnationId"] == incarnation));
            assert!(
                matching.iter().any(|data| {
                    data["changedOperation"]["phase"] == "preparing"
                        && data["changedOperation"]["step"] == "host-validation"
                        && data["changedOperation"]["disposition"].is_null()
                        && data["activeOperations"].as_array().unwrap().iter().any(|operation| {
                            operation["operationId"] == request_id
                        })
                }),
                "the exact request was not active during preparation: {active}"
            );
            let terminal = terminal.unwrap().unwrap();
            assert!(
                terminal["events"].as_array().unwrap().iter().any(|event| {
                    let data = &event["data"];
                    data["system"] == "runtime-host-spawn"
                        && &data["incarnationId"] == incarnation
                        && data["changedOperation"]["operationId"] == request_id
                        && data["changedOperation"]["disposition"] == "failed"
                        && !data["activeOperations"].as_array().unwrap().iter().any(|operation| {
                            operation["operationId"] == request_id
                        })
                }),
                "the revoked request lacks its terminal failure: {terminal}"
            );
        })
    })
    .await;
}

#[tokio::test]
async fn controller_spawn_preserves_gates_permission_policy_and_callback_results() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            for enabled in [false, true] {
                let host = Host::start(ctx, enabled).await;
                let controller = host
                    .owner
                    .create_session(session_config(ctx))
                    .await
                    .unwrap();
                let (requests, mut received) = mpsc::unbounded_channel();
                let (preparations, mut prepared) = mpsc::unbounded_channel();
                host.owner
                    .register_request_handler("runtimeHost.agentRegistryPrepareSpawn", move |params, _| {
                        let preparations = preparations.clone();
                        async move {
                            let request: HostAgentRegistrySpawnRequest =
                                serde_json::from_value(params)?;
                            assert!(!request.request_id.is_empty());
                            if request.request.name.as_deref() == Some("Rejected child") {
                                return Ok(json!({
                                    "kind": "validation-error",
                                    "reason": "invalid-name",
                                    "field": "name",
                                    "message": "The host rejected this child name during preparation",
                                }));
                            }
                            preparations.send(request).unwrap();
                            Ok(json!({ "kind": "prepared" }))
                        }
                    })
                    .unwrap();
                host.owner
                    .register_request_handler("runtimeHost.agentRegistrySpawn", move |params, _| {
                        let requests = requests.clone();
                        async move {
                            let request: HostAgentRegistrySpawnRequest =
                                serde_json::from_value(params)?;
                            requests.send(request).unwrap();
                            Ok(json!({
                                "kind": "spawn-error",
                                "message": "The test host refused this launch",
                                "code": "HOST_REFUSED",
                            }))
                        }
                    })
                    .unwrap();
                host.owner
                    .rpc()
                    .host()
                    .callbacks()
                    .register(HostCallbacksRegisterRequest {
                        agent_registry_spawn: Some(true),
                        controller_session_id: Some(controller.id().to_string()),
                        ..Default::default()
                    })
                    .await
                    .unwrap();
                let request = json!({
                    "cwd": ctx.work_dir(),
                    "model": "gpt-4.1",
                    "name": "Requested child",
                    "initialPrompt": "Send only after attachment",
                });
                let response = host
                    .peer
                    .call("agentRegistry.spawn", Some(request.clone()))
                    .await;
                if !enabled {
                    let error = response.unwrap_err();
                    assert!(error.to_string().contains("not enabled"), "{error}");
                    assert!(received.try_recv().is_err());
                } else {
                    let response = response.unwrap();
                    assert_eq!(response["kind"], "spawn-error", "{response}");
                    assert_eq!(response["code"], "HOST_REFUSED", "{response}");
                    let callback = received.recv().await.unwrap();
                    let preparation = prepared.recv().await.unwrap();
                    assert_eq!(
                        serde_json::to_value(preparation).unwrap(),
                        serde_json::to_value(&callback).unwrap(),
                        "commit must use the exact prepared inputs and request identity"
                    );
                    assert_eq!(callback.controller_session_id, controller.id().as_str());
                    assert_eq!(serde_json::to_value(callback.request).unwrap(), request);
                    assert!(!callback.caller.is_extension);
                    assert_ne!(
                        Some(callback.caller.connection_id),
                        host.owner.in_process_connection_id().map(i64::from)
                    );

                    let validation_error = host
                        .peer
                        .call(
                            "agentRegistry.spawn",
                            Some(json!({ "cwd": ctx.work_dir(), "name": "Rejected child" })),
                        )
                        .await
                        .unwrap();
                    assert_eq!(validation_error["kind"], "validation-error", "{validation_error}");
                    assert_eq!(validation_error["reason"], "invalid-name", "{validation_error}");
                    assert!(received.try_recv().is_err(), "preparation rejection reached commit");

                    let refused = host
                        .peer
                        .call(
                            "agentRegistry.spawn",
                            Some(json!({
                                "cwd": ctx.work_dir(), "permissionMode": "yolo",
                            })),
                        )
                        .await
                        .unwrap();
                    assert_eq!(refused["reason"], "yolo-not-allowed", "{refused}");
                    assert!(received.try_recv().is_err());
                    let allowed = host
                        .owner
                        .call(
                            "session.permissions.setMode",
                            Some(json!({
                                "sessionId": controller.id(),
                                "mode": "allow-all",
                            })),
                        )
                        .await
                        .unwrap();
                    assert_eq!(allowed["mode"], "allow-all", "{allowed}");
                    let allowed_spawn = host
                        .peer
                        .call(
                            "agentRegistry.spawn",
                            Some(json!({ "cwd": ctx.work_dir(), "permissionMode": "yolo" })),
                        )
                        .await
                        .unwrap();
                    assert_eq!(allowed_spawn["code"], "HOST_REFUSED", "{allowed_spawn}");
                    let admitted = received.recv().await.unwrap();
                    assert_eq!(
                        serde_json::to_value(admitted.request).unwrap()["permissionMode"],
                        "yolo"
                    );
                    let foreign = host.peer.create_session(session_config(ctx)).await.unwrap();
                    let error = host
                        .owner
                        .rpc()
                        .host()
                        .callbacks()
                        .register(HostCallbacksRegisterRequest {
                            agent_registry_spawn: Some(true),
                            controller_session_id: Some(foreign.id().to_string()),
                            ..Default::default()
                        })
                        .await
                        .unwrap_err();
                    assert!(
                        error
                            .to_string()
                            .contains("owned by the callback connection"),
                        "{error}"
                    );
                    foreign.disconnect().await.unwrap();
                }
                controller.disconnect().await.unwrap();
                let after_close = host
                    .peer
                    .call("agentRegistry.spawn", Some(request))
                    .await
                    .unwrap_err();
                assert!(
                    after_close.to_string().contains(if enabled {
                        "retired"
                    } else {
                        "not enabled"
                    }),
                    "{after_close}",
                );
                assert!(received.try_recv().is_err());
                host.stop().await;
            }
        })
    })
    .await;
}
