use copilot_sdk::handler::ApproveAllHandler;
use copilot_sdk::rpc::{
    HistoryImportConversationEvent, HistoryImportConversationEventUserMessage,
    HistoryImportUserMessage, SessionHistoryImportConversationParams,
};
use copilot_sdk::{
    CliProgram, Client, CreateSessionOptions, ResumeSessionOptions, RuntimeConnection,
    SessionLifecycleEventType,
};
use serde_json::json;
use std::sync::Arc;

use super::support::{wait_for_lifecycle_event, with_e2e_context, with_e2e_context_no_snapshot};

#[tokio::test]
async fn should_receive_session_created_lifecycle_event() {
    with_e2e_context(
        "client_lifecycle",
        "should_receive_session_created_lifecycle_event",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let created = client.subscribe_lifecycle();
                let session = client
                    .create_session(ctx.approve_all_session_config())
                    .await
                    .expect("create session");

                let event =
                    wait_for_lifecycle_event(created, "session.created lifecycle event", |event| {
                        event.event_type == SessionLifecycleEventType::Created
                    })
                    .await;
                assert_eq!(event.event_type, SessionLifecycleEventType::Created);
                assert_eq!(&event.session_id, session.id());

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

#[tokio::test]
async fn dispose_disconnects_client_and_disposes_rpc_surface_async() {
    with_e2e_context(
        "client_lifecycle",
        "dispose_disconnects_client_and_disposes_rpc_surface_async_true",
        |ctx| {
            Box::pin(async move {
                let client = ctx.start_client().await;
                client.stop().await.expect("stop client");
                assert!(
                    client.call("rpc.ping", Some(json!({}))).await.is_err(),
                    "stopped client should reject RPC calls"
                );
            })
        },
    )
    .await;
}

#[tokio::test]
async fn dispose_disconnects_client_and_disposes_rpc_surface_drop() {
    with_e2e_context(
        "client_lifecycle",
        "dispose_disconnects_client_and_disposes_rpc_surface_async_false",
        |ctx| {
            Box::pin(async move {
                let client = ctx.start_client().await;
                client.force_stop();
                assert!(
                    client.call("rpc.ping", Some(json!({}))).await.is_err(),
                    "force-stopped client should reject RPC calls"
                );
            })
        },
    )
    .await;
}

#[tokio::test]
async fn stop_detaches_both_real_sessions_before_releasing_either_reply() {
    with_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let stdio = || {
                ctx.client_options_with_transport(RuntimeConnection::for_spawned_stdio(
                    Default::default(),
                ))
            };
            let mut gated = stdio();
            let process = gated
                .runtime
                .as_mut()
                .and_then(RuntimeConnection::child_process_options_mut)
                .expect("spawned stdio options");
            let CliProgram::Path(program) = &process.program else {
                panic!("expected resolved runtime program");
            };
            let relay = ctx.repo_root().join("test/harness/stdio-detach-gate.cjs");
            let witness = ctx.work_dir().join("detached.json");
            process.args.splice(
                ..0,
                [
                    relay.into_os_string(),
                    program.as_os_str().to_owned(),
                    witness.as_os_str().to_owned(),
                ],
            );
            process.program = CliProgram::Path(super::support::node_program().into());
            let client = Client::start(gated).await.expect("start gated runtime");
            let mut sessions = Vec::new();
            for _ in 0..2 {
                let session = client
                    .create_session(
                        CreateSessionOptions::default()
                            .with_working_directory(ctx.work_dir())
                            .with_enable_config_discovery(false)
                            .with_permissions(|group| {
                                group.with_on_request(Arc::new(ApproveAllHandler))
                            }),
                    )
                    .await
                    .expect("create session");
                session
                    .rpc()
                    .history()
                    .import_conversation(SessionHistoryImportConversationParams {
                        events: vec![HistoryImportConversationEvent::UserMessage(
                            HistoryImportConversationEventUserMessage {
                                data: HistoryImportUserMessage {
                                    content: session.id().to_string(),
                                    ..Default::default()
                                },
                                ..Default::default()
                            },
                        )],
                    })
                    .await
                    .expect("import session history");
                sessions.push(session);
            }
            client
                .stop()
                .await
                .expect("stop with both detach replies gated");
            let mut detached: Vec<String> = serde_json::from_slice(
                &std::fs::read(witness).expect("read both detached session IDs"),
            )
            .expect("parse detached session IDs");
            detached.sort();
            let mut expected = sessions
                .iter()
                .map(|session| session.id().to_string())
                .collect::<Vec<_>>();
            expected.sort();
            assert_eq!(detached, expected);

            let resumed = Client::start(stdio()).await.expect("start a fresh runtime");
            for original in sessions {
                let id = original.id();
                let session = resumed
                    .resume_session(
                        ResumeSessionOptions::new(id.clone())
                            .with_working_directory(ctx.work_dir())
                            .with_enable_config_discovery(false)
                            .with_permissions(|group| {
                                group.with_on_request(Arc::new(ApproveAllHandler))
                            }),
                    )
                    .await
                    .expect("cold resume");
                let events = session.get_events().await.expect("read resumed history");
                let messages = events
                    .iter()
                    .filter(|event| event.event_type == "user.message")
                    .map(|event| event.data["content"].as_str().expect("message content"))
                    .collect::<Vec<_>>();
                assert_eq!(messages, [id.to_string()]);
            }
            resumed.stop().await.expect("stop the fresh runtime");
        })
    })
    .await;
}

// This test represents github/app#2303: the SDK-hosting GitHub Copilot app
// process exits abruptly, so Client cleanup never runs. The helper starts a
// real CLI client, is terminated through `TerminateProcess`, and relies only
// on Job Object kill-on-close behavior to terminate the CLI.
#[tokio::test]
async fn should_receive_session_updated_lifecycle_event_for_non_ephemeral_activity() {
    with_e2e_context(
        "client_lifecycle",
        "should_receive_session_updated_lifecycle_event_for_non_ephemeral_activity",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let session = client
                    .create_session(ctx.approve_all_session_config())
                    .await
                    .expect("create session");
                let updated = client.subscribe_lifecycle();

                session
                    .client()
                    .call(
                        "session.mode.set",
                        Some(json!({
                            "sessionId": session.id().as_str(),
                            "mode": "plan",
                        })),
                    )
                    .await
                    .expect("set session mode");

                let event =
                    wait_for_lifecycle_event(updated, "session.updated lifecycle event", |event| {
                        event.event_type == SessionLifecycleEventType::Updated
                            && event.session_id == *session.id()
                    })
                    .await;
                assert_eq!(event.event_type, SessionLifecycleEventType::Updated);

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

#[tokio::test]
async fn should_receive_session_deleted_lifecycle_event_when_deleted() {
    with_e2e_context(
        "client_lifecycle",
        "should_receive_session_deleted_lifecycle_event_when_deleted",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let session = client
                    .create_session(ctx.approve_all_session_config())
                    .await
                    .expect("create session");
                let session_id = session.id().clone();
                session
                    .send_and_wait("Say SESSION_DELETED_OK exactly.")
                    .await
                    .expect("send");
                let deleted = client.subscribe_lifecycle();

                client
                    .delete_session(&session_id)
                    .await
                    .expect("delete session");

                let event =
                    wait_for_lifecycle_event(deleted, "session.deleted lifecycle event", |event| {
                        event.event_type == SessionLifecycleEventType::Deleted
                            && event.session_id == session_id
                    })
                    .await;
                assert_eq!(event.event_type, SessionLifecycleEventType::Deleted);

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}
