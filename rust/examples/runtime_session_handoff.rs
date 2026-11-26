//! Hand off remote history through an in-process SDK host, then resume it locally.
//!
//! Set `COPILOT_HOME` to a private directory and `COPILOT_CLI_PATH` to the prepared runtime.
//! Run with `--no-default-features --features local-runtime -- <metadata.json> <absolute-cwd>`.
//! The metadata file contains one remote entry from `client.rpc().sessions().list(...)`.
//! Repository mismatches are refused. This example does not run a model turn.

use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use copilot_sdk::handler::ApproveAllHandler;
use copilot_sdk::rpc::{
    RemoteSessionMetadataValue, SessionHandoffProgressNotification, SessionOpenOptions,
    SessionOpenParams, SessionsOpenHandoff, SessionsOpenStatus,
};
use copilot_sdk::session_handoff::SessionHandoffHandler;
use copilot_sdk::{
    Client, ClientOptions, InProcessRuntimeOptions, Result, ResumeSessionOptions, RuntimeConnection,
};

struct Progress;

#[async_trait]
impl SessionHandoffHandler for Progress {
    async fn progress(&self, update: SessionHandoffProgressNotification) -> Result<()> {
        println!(
            "Handoff: {:?} {:?}",
            update.update.step, update.update.status
        );
        Ok(())
    }
}

async fn demonstrate(
    client: &Client,
    metadata: RemoteSessionMetadataValue,
    cwd: PathBuf,
) -> std::result::Result<(), Box<dyn std::error::Error>> {
    client.register_session_handoff_handler(Arc::new(Progress))?;
    let mut options = SessionOpenOptions::default();
    options.working_directory = Some(cwd.to_string_lossy().into_owned());
    options.enable_config_discovery = Some(false);
    options
        .github
        .get_or_insert_with(Default::default)
        .fetch_managed_settings = Some(false);
    options
        .hooks
        .get_or_insert_with(Default::default)
        .enable_repository_hooks = Some(false);
    let mut handoff = SessionsOpenHandoff::default();
    handoff.metadata = metadata;
    handoff.callback_id = Some("example".to_owned());
    handoff.options = Some(options);
    let result = client
        .rpc()
        .sessions()
        .open(SessionOpenParams::Handoff(handoff))
        .await?;
    if result.status != SessionsOpenStatus::HandedOff {
        return Err(format!("Handoff failed: {:?}", result.status).into());
    }
    let session_id = result.session_id.ok_or("Handoff returned no session ID")?;
    let session = client
        .resume_session(
            ResumeSessionOptions::new(session_id.clone())
                .with_enable_config_discovery(false)
                .with_github(|group| group.with_fetch_managed_settings(false))
                .with_permissions(|group| group.with_on_request(Arc::new(ApproveAllHandler))),
        )
        .await?;
    let history = session.get_events().await?;
    println!("Local session resumed");
    println!("Persisted history: {} events", history.len());
    println!(
        "Local working directory: {}",
        session.rpc().metadata().snapshot().await?.working_directory
    );
    if !history
        .iter()
        .any(|event| event.event_type == "session.handoff")
    {
        return Err("The resumed history has no handoff event".into());
    }
    session.disconnect().await?;
    Ok(())
}

#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args_os().skip(1);
    let metadata_path = args
        .next()
        .ok_or("Pass a metadata JSON file and absolute working directory")?;
    let cwd = PathBuf::from(args.next().ok_or("Pass an absolute working directory")?);
    let metadata = serde_json::from_slice(&std::fs::read(metadata_path)?)?;
    let home = std::env::var_os("COPILOT_HOME").ok_or("Set COPILOT_HOME to a private directory")?;
    let client = Client::start(
        ClientOptions::new(copilot_sdk::ClientMode::CodingAgent)
            .with_runtime(RuntimeConnection::InProcess(
                InProcessRuntimeOptions::default()
                    .with_owned(|owned| owned.with_base_directory(PathBuf::from(home))),
            ))
            .with_observability(|group| group.with_disable_client_telemetry(true)),
    )
    .await?;
    println!(
        "SDK-owned in-process host: {:?}",
        client.in_process_server_id()
    );
    let result = demonstrate(&client, metadata, cwd).await;
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
