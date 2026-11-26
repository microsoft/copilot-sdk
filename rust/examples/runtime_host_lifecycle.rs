//! Run with `--no-default-features --features local-runtime` and a prepared
//! same-checkout `COPILOT_CLI_PATH`. This example disables telemetry uploads.
//! Pass `-- --in-process` to inspect the client's native host identity.
//! Add `--managed-plugins` to retry the session's managed plugins through that host.
//! Add `--configured-host` to create and resume a session with runtime-owned storage admission.
//! In-process mode inherits host settings. Set `COPILOT_GITHUB_TELEMETRY_DISABLED=true`,
//! `COPILOT_TELEMETRY_DISABLED=true`, and `DO_NOT_TRACK=1` before running it.
//! Pass `-- --observe-ahp-exit` to observe a real loopback listener across a restart.
//! A retired subscription cannot cancel the replacement that uses the same callback.

use std::error::Error;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use copilot_sdk::handler::ApproveAllHandler;
use copilot_sdk::rpc::{
    HostDisposeRequest, HostLocalServerOptions, HostStartRequest, SessionsSaveRequest,
};
use copilot_sdk::types::CreateSessionOptions;
use copilot_sdk::{
    AhpHostExitReason, Client, ClientOptions, GetStatusResponse, InProcessRuntimeOptions,
    ResumeSessionOptions, RuntimeConnection, RuntimeHostConfiguration, SpawnedProcessOptions,
};

async fn demonstrate(
    client: &Client,
    managed_plugins: bool,
    configured_host: Option<&Path>,
    observe_exit: bool,
) -> Result<(), Box<dyn Error>> {
    let pending = client.begin_call("status.get", None)?;
    println!("Status request queued before awaiting its response");
    let status: GetStatusResponse = serde_json::from_value(pending.await?)?;
    println!("Runtime version: {}", status.version);
    println!("Protocol version: {}", status.protocol_version);
    if managed_plugins || configured_host.is_some() {
        let configuration = CreateSessionOptions::default()
            .with_permissions(|group| group.with_on_request(Arc::new(ApproveAllHandler)))
            .with_github(|group| group.with_fetch_managed_settings(managed_plugins));
        let configuration = if configured_host.is_some() {
            configuration.with_name("SDK host example")
        } else {
            configuration
        };
        let session = client.create_session(configuration).await?;
        if managed_plugins {
            let retried = session.rpc().plugins().retry_managed().await?;
            println!(
                "Managed plugin retry returned {} outcomes",
                retried.plugins.len()
            );
        }
        if let Some(expected_directory) = configured_host {
            client
                .rpc()
                .sessions()
                .save(SessionsSaveRequest {
                    session_id: session.id().clone(),
                })
                .await?;
            let metadata = session.rpc().metadata().snapshot().await?;
            println!(
                "Host default working directory applied: {}",
                Path::new(&metadata.working_directory) == expected_directory
            );
            let session_id = session.id().clone();
            session.disconnect().await?;
            let resumed = client
                .resume_session(
                    ResumeSessionOptions::new(session_id.clone()).with_permissions(|group| {
                        group.with_on_request(Arc::new(ApproveAllHandler))
                    }),
                )
                .await?;
            println!(
                "Resumed the same runtime-owned session: {}",
                resumed.id() == session_id
            );
            resumed.disconnect().await?;
        } else {
            session.disconnect().await?;
        }
    }
    if observe_exit {
        let host_id = uuid::Uuid::new_v4().to_string();
        let (sender, mut exits) = tokio::sync::mpsc::unbounded_channel();
        let callback = Arc::new(move |exit: copilot_sdk::AhpHostExit| {
            let _ = sender.send(exit);
        });
        let options = HostStartRequest {
            host_id: host_id.clone(),
            authentication: Some(serde_json::json!({ "mode": "none" })),
            allow_creating_new_sessions: Some(false),
            local_server: Some(HostLocalServerOptions {
                hostname: Some("127.0.0.1".to_owned()),
                port: Some(0),
                require_connection_token: Some(false),
                token: None,
            }),
            ..Default::default()
        };
        let mut previous = None;
        for _ in 0..2 {
            let subscription = client.observe_ahp_host_exit(host_id.clone(), callback.clone())?;
            drop(previous.take());
            println!("AHP observer registered before host.start: {host_id}");
            let host = client.rpc().host().start(options.clone()).await?;
            println!("AHP loopback listener started: {:?}", host.url);
            client
                .rpc()
                .host()
                .dispose(HostDisposeRequest {
                    host_id: host_id.clone(),
                })
                .await?;
            let exit = tokio::time::timeout(Duration::from_secs(10), exits.recv())
                .await?
                .ok_or_else(|| {
                    std::io::Error::other("AHP exit observer closed without an event")
                })?;
            println!("host.exited: {}", serde_json::to_string(&exit)?);
            if exit.host_id != host_id || exit.reason != AhpHostExitReason::Disposed {
                return Err(std::io::Error::other("Unexpected AHP exit identity or reason").into());
            }
            previous = Some(subscription);
        }
        drop(previous);
        println!("AHP observer dropped after disposal");
    }
    Ok(())
}

#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let home = tempfile::tempdir()?;
    let in_process = std::env::args().any(|arg| arg == "--in-process");
    let managed_plugins = std::env::args().any(|arg| arg == "--managed-plugins");
    let configured_host = std::env::args().any(|arg| arg == "--configured-host");
    let in_process = in_process || configured_host;
    let transport = if in_process {
        RuntimeConnection::for_in_process(InProcessRuntimeOptions::new())
    } else {
        RuntimeConnection::for_spawned_stdio(SpawnedProcessOptions::new().with_env(
            std::env::vars_os().chain([
                ("COPILOT_GITHUB_TELEMETRY_DISABLED".into(), "true".into()),
                ("COPILOT_TELEMETRY_DISABLED".into(), "true".into()),
                ("DO_NOT_TRACK".into(), "1".into()),
            ]),
        ))
    };
    let mut runtime = transport;
    runtime
        .owned_options_mut()
        .expect("owned runtime")
        .base_directory = Some(home.path().to_owned());
    let options = ClientOptions::new(copilot_sdk::ClientMode::CodingAgent)
        .with_runtime(runtime)
        .with_github(|group| group.with_token("").with_use_logged_in_user(false))
        .with_observability(|group| group.with_disable_client_telemetry(true));
    let client = if configured_host {
        Client::start_with_host_configuration(
            options,
            RuntimeHostConfiguration {
                working_directory: Some(home.path().to_string_lossy().into_owned()),
                claim_session_on_resume: Some(true),
                persist_unnamed_sessions: Some(true),
                ..Default::default()
            },
        )
        .await?
    } else {
        Client::start(options).await?
    };
    println!("Runtime started through the SDK");
    println!("In-process server ID: {:?}", client.in_process_server_id());
    println!(
        "In-process connection ID: {:?}",
        client.in_process_connection_id()
    );
    let observe_exit = std::env::args().any(|arg| arg == "--observe-ahp-exit");
    let result = demonstrate(
        &client,
        managed_plugins,
        configured_host.then_some(home.path()),
        observe_exit,
    )
    .await;
    let stopped = client.stop().await;
    if let Err(error) = result {
        if let Err(shutdown_error) = stopped {
            eprintln!("Shutdown also failed: {shutdown_error}");
        }
        return Err(error);
    }
    stopped?;
    println!(
        "In-process server after shutdown: {:?}",
        client.in_process_server_id()
    );
    println!("Runtime shutdown complete");
    Ok(())
}
