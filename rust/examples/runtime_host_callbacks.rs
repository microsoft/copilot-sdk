//! Supply foreground and controller-local launch callbacks without a TypeScript session host.
//! Set `COPILOT_HOME` to a private directory and `COPILOT_CLI_PATH` to the same-checkout runtime.
//! Run with `--no-default-features --features local-runtime`.
//! This example separates pure asynchronous validation from an admitted launch-only callback.
//! It clears a rejected selection, selects a session, and validates launches without a child.

use std::sync::Arc;

use copilot_sdk::handler::ApproveAllHandler;
use copilot_sdk::rpc::{
    AgentRegistrySpawnRequest, HostAgentRegistryPrepareSpawnResult, HostAgentRegistrySpawnPrepared,
    HostAgentRegistrySpawnPreparedKind, HostAgentRegistrySpawnRequest,
    HostCallbacksRegisterRequest, HostForegroundSessionRequest,
};
use copilot_sdk::{
    Client, ClientOptions, CreateSessionOptions, InProcessRuntimeOptions, RuntimeConnection,
    RuntimeHostConfiguration,
};
use serde_json::json;

async fn demonstrate(
    client: &Client,
    home: &std::path::Path,
) -> Result<(), Box<dyn std::error::Error>> {
    let session = client
        .create_session(
            CreateSessionOptions::default()
                .with_name("SDK host callbacks")
                .with_working_directory(home)
                .with_enable_config_discovery(false)
                .with_github(|group| group.with_fetch_managed_settings(false))
                .with_permissions(|group| group.with_on_request(Arc::new(ApproveAllHandler))),
        )
        .await?;
    let rejected = client
        .create_session(
            CreateSessionOptions::default()
                .with_name("Rejected foreground selection")
                .with_working_directory(home)
                .with_enable_config_discovery(false)
                .with_github(|group| group.with_fetch_managed_settings(false))
                .with_permissions(|group| group.with_on_request(Arc::new(ApproveAllHandler))),
        )
        .await?;
    let rejected_id = rejected.id().clone();
    client.register_request_handler(
        "runtimeHost.foregroundSessionChange",
        move |params, client| {
            let rejected_id = rejected_id.clone();
            async move {
                let request: HostForegroundSessionRequest = serde_json::from_value(params)?;
                match request.session_id {
                    Some(session_id) => {
                        println!("Foreground callback selected a session");
                        if session_id == rejected_id {
                            client
                                .call("sessions.close", Some(json!({ "sessionId": session_id })))
                                .await?;
                        }
                    }
                    None => println!("Foreground callback cleared the rejected selection"),
                }
                Ok(json!({ "success": true }))
            }
        },
    )?;
    client.register_request_handler(
        "runtimeHost.agentRegistryPrepareSpawn",
        |params, _client| async {
            let request: HostAgentRegistrySpawnRequest = serde_json::from_value(params)?;
            println!("Pure preparation callback: {}", request.request_id);
            match tokio::fs::metadata(&request.request.cwd).await {
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(json!({
                    "kind": "validation-error",
                    "reason": "cwd-not-found",
                    "field": "cwd",
                    "message": "The requested working directory does not exist",
                })),
                Err(error) => Err(error.into()),
                Ok(metadata) if !metadata.is_dir() => Ok(json!({
                    "kind": "validation-error",
                    "reason": "cwd-not-directory",
                    "field": "cwd",
                    "message": "The requested working directory is not a directory",
                })),
                Ok(_) => Ok(serde_json::to_value(
                    HostAgentRegistryPrepareSpawnResult::Prepared(HostAgentRegistrySpawnPrepared {
                        kind: HostAgentRegistrySpawnPreparedKind::Prepared,
                    }),
                )?),
            }
        },
    )?;
    client.register_request_handler("runtimeHost.agentRegistrySpawn", |params, _client| async {
        let request: HostAgentRegistrySpawnRequest = serde_json::from_value(params)?;
        println!("Launch-only callback: {}", request.request_id);
        // A real host launches synchronously here, then awaits its registry entry.
        // This example refuses instead, so it leaves no child process behind.
        Ok(json!({
            "kind": "spawn-error",
            "code": "EXAMPLE_NO_CHILD",
            "message": "This example does not start child processes",
        }))
    })?;
    client
        .rpc()
        .host()
        .callbacks()
        .register(HostCallbacksRegisterRequest {
            foreground_session_change: Some(true),
            agent_registry_spawn: Some(true),
            controller_session_id: Some(session.id().to_string()),
        })
        .await?;
    if client
        .set_foreground_session_id(rejected.id())
        .await
        .is_ok()
    {
        return Err("The runtime admitted a closed foreground session".into());
    }
    if client.get_foreground_session_id().await?.is_some() {
        return Err("The runtime retained the rejected foreground session".into());
    }
    rejected.disconnect().await?;
    let selected = client
        .call(
            "session.setForeground",
            Some(json!({ "sessionId": session.id() })),
        )
        .await?;
    if selected["success"] != true {
        return Err(format!("Foreground selection failed: {selected}").into());
    }
    if client.get_foreground_session_id().await?.as_ref() != Some(session.id()) {
        return Err("The runtime did not retain the selected session".into());
    }
    let spawn = client
        .rpc()
        .agent_registry()
        .spawn(AgentRegistrySpawnRequest {
            cwd: home
                .join("missing-child-directory")
                .to_string_lossy()
                .into_owned(),
            ..Default::default()
        })
        .await?;
    println!("Preparation rejection: {}", serde_json::to_string(&spawn)?);
    if serde_json::to_value(&spawn)?["reason"] != "cwd-not-found" {
        return Err("The missing directory did not fail preparation".into());
    }
    let admitted = client
        .rpc()
        .agent_registry()
        .spawn(AgentRegistrySpawnRequest {
            cwd: home.to_string_lossy().into_owned(),
            ..Default::default()
        })
        .await?;
    println!(
        "Admitted launch result: {}",
        serde_json::to_string(&admitted)?
    );
    if serde_json::to_value(&admitted)?["code"] != "EXAMPLE_NO_CHILD" {
        return Err("The valid request did not reach the launch-only callback".into());
    }
    client
        .rpc()
        .host()
        .callbacks()
        .register(HostCallbacksRegisterRequest::default())
        .await?;
    session.disconnect().await?;
    Ok(())
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let home = std::env::var_os("COPILOT_HOME")
        .map(std::path::PathBuf::from)
        .ok_or("Set COPILOT_HOME to a private directory")?;
    let client = Client::start_with_host_configuration(
        ClientOptions::new(copilot_sdk::ClientMode::CodingAgent)
            .with_runtime(RuntimeConnection::InProcess(
                InProcessRuntimeOptions::new().with_owned(|owned| owned.with_base_directory(&home)),
            ))
            .with_github(|group| group.with_token(""))
            .with_github(|group| group.with_use_logged_in_user(false))
            .with_observability(|group| group.with_disable_client_telemetry(true)),
        RuntimeHostConfiguration {
            feature_flags: Some(json!({ "SESSIONS_SIDEBAR_TAB": true })),
            ..Default::default()
        },
    )
    .await?;
    let result = demonstrate(&client, &home).await;
    let stopped = client.stop().await;
    if let Err(error) = result {
        if let Err(shutdown) = stopped {
            eprintln!("Runtime shutdown also failed: {shutdown}");
        }
        return Err(error);
    }
    stopped?;
    Ok(())
}
