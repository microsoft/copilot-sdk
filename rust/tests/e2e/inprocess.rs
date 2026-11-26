use std::sync::Arc;

use copilot_sdk::handler::ApproveAllHandler;
use copilot_sdk::rpc::{
    HostCallbacksRegisterRequest, NameSetRequest, SessionsCheckInUseRequest, SessionsSaveRequest,
};
use copilot_sdk::{
    Client, ClientOptions, CreateSessionOptions, InProcessRuntimeOptions, ResumeSessionOptions,
    RuntimeConnection, RuntimeHostConfiguration, SessionId,
};

use super::support::{E2eContext, with_e2e_context};

/// Starts an in-process client, performs a round-trip, and stops cleanly.
/// Fails hard if the in-process runtime library cannot be loaded.
#[tokio::test]
async fn should_start_ping_and_stop_inprocess_client() {
    with_e2e_context("client", "should_start_ping_and_stop_stdio_client", |ctx| {
        Box::pin(async move {
            let mut sibling_context =
                E2eContext::new_with_cli("client", "should_start_ping_and_stop_stdio_client", None)
                    .await
                    .expect("create sibling context");
            let (client, sibling) = tokio::join!(
                ctx.start_inprocess_client(),
                sibling_context.start_inprocess_client()
            );
            assert_ne!(
                client.in_process_connection_id(),
                sibling.in_process_connection_id()
            );
            let timings = client.startup_timings().expect("startup timings");
            assert!(timings.program_resolve_ms.is_some());
            assert!(timings.process_spawn_ms.is_none());
            assert!(timings.port_wait_ms.is_none());
            assert!(timings.total_ms >= timings.transport_setup_ms);
            assert!(timings.total_ms >= timings.handshake_ms);

            let response = client
                .ping(Some("hello from rust in-process"))
                .await
                .expect("ping over in-process FFI transport");
            assert_eq!(response.message, "pong: hello from rust in-process");
            assert!(!response.timestamp.is_empty());

            let status = client.get_status().await.expect("get status");
            assert!(status.protocol_version > 0);

            let (first, second) =
                tokio::join!(client.ping(Some("first")), sibling.ping(Some("second")));
            assert_eq!(first.expect("first reply").message, "pong: first");
            assert_eq!(second.expect("second reply").message, "pong: second");
            client.stop().await.expect("stop in-process client");
            assert_eq!(
                sibling
                    .ping(Some("survivor"))
                    .await
                    .expect("sibling remains live")
                    .message,
                "pong: survivor"
            );
            sibling.stop().await.expect("stop sibling");
            sibling_context
                .cleanup(true)
                .await
                .expect("clean up sibling");
        })
    })
    .await;
}

#[tokio::test]
async fn owned_host_defaults_apply_before_session_creation() {
    super::support::with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            let configured_home = ctx.home_dir().join("configured-host");
            let configuration = RuntimeHostConfiguration {
                config_directory: Some(configured_home.to_string_lossy().into_owned()),
                working_directory: Some(ctx.work_dir().to_string_lossy().into_owned()),
                persist_unnamed_sessions: Some(true),
                ..Default::default()
            };
            let client = Client::start_with_host_configuration(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::new(),
                ))
                .with_github(|group| group.with_token(super::support::DEFAULT_TEST_TOKEN)),
                configuration,
            )
            .await
            .expect("start configured SDK-owned host");
            // Attached connections cannot specify startup configuration. Check their
            // controller admission at the supported callback registration boundary.
            let attached = ClientOptions::new(copilot_sdk::ClientMode::CodingAgent).with_runtime(
                RuntimeConnection::AttachedInProcess(
                    copilot_sdk::AttachedInProcessRuntimeOptions {
                        server_id: client.in_process_server_id().unwrap(),
                        runtime_library_path: client.in_process_library_path().unwrap(),
                        session_binding: None,
                        run_on_ui_thread: None,
                        file_logging_path: None,
                    },
                ),
            );
            let observer = Client::start(attached).await.expect("attach an observer");
            let error = observer
                .rpc()
                .host()
                .callbacks()
                .register(HostCallbacksRegisterRequest::default())
                .await
                .expect_err("an observer cannot replace the owner's callbacks");
            assert!(
                error.to_string().contains("SDK-owned runtime controller"),
                "{error}"
            );
            observer.stop().await.expect("stop the observer");
            let session = client
                .create_session(
                    CreateSessionOptions::default()
                        .with_permissions(|group| {
                            group.with_on_request(Arc::new(ApproveAllHandler))
                        })
                        .with_enable_config_discovery(false),
                )
                .await
                .expect("create with host defaults");
            let metadata = session.rpc().metadata().snapshot().await.unwrap();
            assert_eq!(
                std::path::Path::new(&metadata.working_directory),
                ctx.work_dir()
            );
            client
                .rpc()
                .sessions()
                .save(SessionsSaveRequest {
                    session_id: session.id().clone(),
                })
                .await
                .expect("flush the session before observing storage");
            let journal = std::fs::read_to_string(
                configured_home
                    .join("session-state")
                    .join(session.id().as_str())
                    .join("events.jsonl"),
            )
            .expect("an unnamed session must persist its initial state");
            assert!(
                journal.lines().any(|line| {
                    serde_json::from_str::<serde_json::Value>(line).unwrap()["type"]
                        == "session.start"
                }),
                "forced persistence must include the initial session event: {journal}"
            );
            // The catalog omits unnamed sessions with only housekeeping events.
            session
                .rpc()
                .name()
                .set(NameSetRequest {
                    name: "Configured host session".to_owned(),
                })
                .await
                .expect("give the persisted session a catalog name");
            assert!(
                client
                    .list_sessions(None)
                    .await
                    .unwrap()
                    .iter()
                    .any(|entry| entry.session_id == session.id()),
                "the host catalog must use the configured store"
            );
            client.stop().await.expect("stop configured host");
        })
    })
    .await;
}

#[tokio::test]
async fn owned_host_mcp_defaults_apply_to_remote_handoff() {
    use std::collections::HashMap;
    use std::panic::AssertUnwindSafe;

    use futures_util::FutureExt;
    use serde_json::json;

    super::support::with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_copilot_user_response(super::support::DEFAULT_TEST_TOKEN, json!({
                "login": "host-default-user", "id": 12345, "copilot_plan": "individual_pro",
                "analytics_tracking_id": "host-default-user",
                "endpoints": { "api": ctx.network_fixture_url(), "telemetry": "https://sdk-hydro-collector.example.com" },
            }));
            let logs = [
                json!({ "role": "user", "content": "Retain the remote history.", "source": "string" }),
                json!({ "id": "host-default-reply", "choices": [{ "delta": { "content": "Remote reply." } }] }),
            ].iter().map(|log| format!("data: {log}\n\n")).collect::<String>();
            ctx.configure_network_fixture(&json!({
                "httpResponses": [
                    { "path": "/agents/tasks/host-defaults",
                      "body": { "id": "host-defaults", "sessions": [{ "id": "host-source" }] } },
                    { "path": "/agents/sessions/host-source/logs", "body": logs, "contentType": "text/event-stream" },
                    { "path": "/models", "body": { "data": [{
                        "id": "sdk-handoff-family-model",
                        "name": "Handoff family model",
                        "model_picker_enabled": true,
                        "capabilities": {
                            "type": "chat",
                            "family": "sdk-handoff-family-model",
                            "supports": {},
                            "limits": { "max_context_window_tokens": 128000 },
                        },
                    }] } },
                ],
            })).unwrap();
            std::fs::write(
                ctx.home_dir().join("settings.json"),
                json!({ "modelFamily": "gpt-5.4" }).to_string(),
            ).unwrap();
            for args in [
                vec!["init", "--quiet"],
                vec!["remote", "add", "origin", "https://github.com/test-org/test-repo.git"],
            ] {
                let mut command = std::process::Command::new("git");
                command.args(args).current_dir(ctx.work_dir());
                #[cfg(windows)]
                {
                    use std::os::windows::process::CommandExt;
                    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
                    command.creation_flags(CREATE_NO_WINDOW);
                }
                assert!(command.status().unwrap().success());
            }
            let harness = ctx.repo_root().join("test/harness");
            let configuration = RuntimeHostConfiguration {
                    config_directory: Some(ctx.home_dir().to_string_lossy().into_owned()),
                    context_management_tools_config_preference: Some(false),
                    additional_mcp_servers: Some(HashMap::from([(
                        "host-meta".to_owned(),
                        json!({
                            "command": if cfg!(windows) { "node.exe" } else { "node" },
                            "args": [harness.join("test-mcp-meta-echo-server.mjs")],
                            "cwd": harness,
                            "tools": ["*"],
                        }),
                    )])),
                    ..Default::default()
                };
            let mut options = ctx.client_options_with_transport(RuntimeConnection::InProcess(
                InProcessRuntimeOptions::new(),
            )).with_github(|group| group.with_token(super::support::DEFAULT_TEST_TOKEN));
            // Handoff prefers the explicit API URL over the authenticated user's endpoint.
            options.internal_test_options.as_mut().unwrap().copilot_api_url =
                Some(ctx.network_fixture_url().to_owned());
            let client = Client::start_with_host_configuration(options, configuration).await.unwrap();
            let outcome = AssertUnwindSafe(async {
                let opened = client.call("sessions.open", Some(json!({
                    "kind": "handoff",
                    "metadata": {
                        "sessionId": "host-defaults", "resourceId": "host-defaults", "isRemote": true,
                        "startTime": "2026-01-01T00:00:00Z", "modifiedTime": "2026-01-01T00:00:00Z",
                        "remoteSessionIds": ["host-source"], "taskType": "cca",
                        "repository": { "owner": "test-org", "name": "test-repo", "branch": "" },
                    },
                    "options": {
                        "workingDirectory": ctx.work_dir(), "configDirectory": ctx.home_dir(),
                        "model": "sdk-handoff-family-model", "inference": { "reasoningEffort": "high" },
                        "enableConfigDiscovery": false,
                        "github": { "fetchManagedSettings": false },
                        "hooks": { "enableRepositoryHooks": false },
                    },
                }))).await.unwrap();
                assert_eq!(opened["status"], "handed_off", "{opened}");
                client.call("sessions.open", Some(json!({
                    "kind": "attach", "sessionId": opened["sessionId"],
                }))).await.unwrap();
                let servers = client.call("session.mcp.list", Some(json!({
                    "sessionId": opened["sessionId"],
                }))).await.unwrap();
                assert!(servers["servers"].as_array().unwrap().iter().any(|server| server["name"] == "host-meta"),
                    "handoff omitted the configured host MCP server: {servers}");
            }).catch_unwind().await;
            let stopped = client.stop().await;
            if let Err(panic) = outcome {
                assert!(stopped.is_ok(), "host shutdown also failed: {stopped:?}");
                std::panic::resume_unwind(panic);
            }
            stopped.unwrap();
        })
    }).await;
}

#[tokio::test]
async fn owned_host_resume_claims_storage_before_loading_history() {
    super::support::with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            let writer = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::SpawnedStdio(
                    Default::default(),
                ))
                .with_github(|group| group.with_token(super::support::DEFAULT_TEST_TOKEN)),
            )
            .await
            .expect("start independent SDK writer");
            let session = writer
                .create_session(
                    ctx.approve_all_session_config()
                        .with_enable_config_discovery(false)
                        .with_name("Resume admission fixture"),
                )
                .await
                .expect("create persisted session");
            let session_id = session.id().clone();
            writer
                .rpc()
                .sessions()
                .save(SessionsSaveRequest {
                    session_id: session_id.clone(),
                })
                .await
                .expect("flush the writer's history");
            let configuration = RuntimeHostConfiguration {
                claim_session_on_resume: Some(true),
                reject_resume_of_session_in_use: Some(true),
                working_directory: Some(ctx.work_dir().to_string_lossy().into_owned()),
                ..Default::default()
            };
            let owner = Client::start_with_host_configuration(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::new(),
                ))
                .with_github(|group| group.with_token(super::support::DEFAULT_TEST_TOKEN)),
                configuration,
            )
            .await
            .expect("start exclusive SDK-owned host");
            let resume = || {
                ResumeSessionOptions::new(session_id.clone())
                    .with_permissions(|group| group.with_on_request(Arc::new(ApproveAllHandler)))
                    .with_enable_config_discovery(false)
            };
            let error = owner
                .resume_session(resume())
                .await
                .err()
                .expect("another process owns the session");
            assert!(
                error.to_string().contains(&format!(
                    "Session {session_id} is in use by another Copilot process."
                )),
                "{error}"
            );
            writer.stop().await.expect("release the independent writer");

            let resumed = owner.resume_session(resume()).await.expect("cold resume");
            assert_eq!(resumed.id(), &session_id);
            let equivalent_store = owner
                .resume_session(resume().with_config_directory(ctx.home_dir().join(".")))
                .await
                .expect("an equivalent directory spelling must retain the resident store");
            assert_eq!(equivalent_store.id(), &session_id);
            let observer = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::SpawnedStdio(
                    Default::default(),
                ))
                .with_github(|group| group.with_token(super::support::DEFAULT_TEST_TOKEN)),
            )
            .await
            .expect("start independent ownership observer");
            let request = || SessionsCheckInUseRequest {
                session_ids: vec![session_id.to_string()],
            };
            assert_eq!(
                observer
                    .rpc()
                    .sessions()
                    .check_in_use(request())
                    .await
                    .unwrap()
                    .in_use,
                vec![session_id.to_string()],
                "an idle resume must own storage before its first turn"
            );
            let other_store = ctx.home_dir().join("other-store");
            std::fs::create_dir_all(&other_store).unwrap();
            #[cfg(unix)]
            {
                let linked_child = other_store.join("child");
                std::fs::create_dir_all(&linked_child).unwrap();
                let copied_session = other_store.join("session-state").join(session_id.as_str());
                std::fs::create_dir_all(&copied_session).unwrap();
                std::fs::copy(
                    ctx.home_dir()
                        .join("session-state")
                        .join(session_id.as_str())
                        .join("events.jsonl"),
                    copied_session.join("events.jsonl"),
                )
                .unwrap();
                let link = ctx.home_dir().join("store-link");
                std::os::unix::fs::symlink(linked_child, &link).unwrap();
                let result = owner
                    .resume_session(resume().with_config_directory(link.join("..")))
                    .await
                    .expect("discovery changes must preserve the owned resident store");
                assert_eq!(result.id(), &session_id);
                assert_eq!(
                    observer
                        .rpc()
                        .sessions()
                        .check_in_use(request())
                        .await
                        .unwrap()
                        .in_use,
                    vec![session_id.to_string()],
                    "a linked discovery directory must not release the resident store"
                );
                let alias = ctx.home_dir().join("same-store-link");
                std::os::unix::fs::symlink(ctx.home_dir(), &alias).unwrap();
                owner
                    .resume_session(resume().with_config_directory(alias))
                    .await
                    .expect("an alias of the resident store must preserve its ownership");
                assert_eq!(
                    observer
                        .rpc()
                        .sessions()
                        .check_in_use(request())
                        .await
                        .unwrap()
                        .in_use,
                    vec![session_id.to_string()],
                    "an equivalent store alias must not replace its existing lock"
                );
            }
            let resident = owner
                .resume_session(resume().with_config_directory(other_store))
                .await
                .expect("a discovery directory change must retain the resident store");
            assert_eq!(resident.id(), &session_id);
            assert_eq!(
                observer
                    .rpc()
                    .sessions()
                    .check_in_use(request())
                    .await
                    .unwrap()
                    .in_use,
                vec![session_id.to_string()],
                "a discovery directory change must retain existing ownership"
            );

            let unreadable_id = SessionId::new(uuid::Uuid::new_v4().to_string());
            let unreadable = ctx
                .home_dir()
                .join("session-state")
                .join(unreadable_id.as_str());
            std::fs::create_dir_all(unreadable.join("events.jsonl")).unwrap();
            owner
                .resume_session(
                    ResumeSessionOptions::new(unreadable_id.clone()).with_permissions(|group| {
                        group.with_on_request(Arc::new(ApproveAllHandler))
                    }),
                )
                .await
                .err()
                .expect("a history file that is a directory cannot resume");
            assert!(
                observer
                    .rpc()
                    .sessions()
                    .check_in_use(SessionsCheckInUseRequest {
                        session_ids: vec![unreadable_id.to_string()],
                    })
                    .await
                    .unwrap()
                    .in_use
                    .is_empty(),
                "a failed history load must release its new claim"
            );
            owner.stop().await.expect("stop the resumed owner");
            assert!(
                observer
                    .rpc()
                    .sessions()
                    .check_in_use(request())
                    .await
                    .unwrap()
                    .in_use
                    .is_empty(),
                "host shutdown must release resumed storage"
            );
            observer.stop().await.expect("stop the ownership observer");
        })
    })
    .await;
}
