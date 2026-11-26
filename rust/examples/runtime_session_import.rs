//! Import a historical conversation through an SDK-owned runtime without a TypeScript host.
//! Set `COPILOT_HOME` to an absolute private directory.
//! Run with `cargo run --no-default-features --features local-runtime --example runtime_session_import`.
//! Supply the same-checkout executable through `COPILOT_CLI_PATH`.

use copilot_sdk::rpc::SessionImportRequest;
use copilot_sdk::{Client, ClientOptions, InProcessRuntimeOptions, RuntimeConnection};
use serde_json::json;

async fn demonstrate(
    client: &Client,
    home: &std::path::Path,
) -> Result<(), Box<dyn std::error::Error>> {
    let records = [
        json!({
            "type": "session", "version": 1, "externalId": "other-app-conversation",
            "title": "Imported conversation", "createdAt": "2024-02-29T12:34:56Z",
        }),
        json!({
            "type": "message", "id": "external-user", "timestamp": "2024-02-29T12:34:56Z",
            "role": "user", "content": [{ "type": "text", "text": "Remember the historical conversation." }],
        }),
        json!({
            "type": "message", "id": "external-assistant", "timestamp": "2024-02-29T12:34:57Z",
            "role": "assistant", "content": [{ "type": "text", "text": "The imported history remains available." }],
        }),
    ];
    let mut request = SessionImportRequest {
        input_path: home
            .join("historical-conversation.jsonl")
            .to_string_lossy()
            .into_owned(),
        jsonl: records
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join("\n"),
        default_working_directory: home.to_string_lossy().into_owned(),
        dry_run: Some(true),
        working_directory: Some(home.to_string_lossy().into_owned()),
        ..Default::default()
    };
    let validated = client.rpc().sessions().import(request.clone()).await?;
    if !validated.ok || validated.imported.sessions != 0 || validated.session_id.is_some() {
        return Err(format!("Dry run failed: {validated:?}").into());
    }
    println!(
        "Dry run: {} messages validated; no session created",
        validated.imported.messages
    );
    request.dry_run = Some(false);
    let imported = client.rpc().sessions().import(request).await?;
    if !imported.ok || imported.imported.sessions != 1 {
        return Err(format!("Import failed: {imported:?}").into());
    }
    imported.session_id.ok_or("Missing imported session ID")?;
    println!(
        "Imported {} session with {} messages",
        imported.imported.sessions, imported.imported.messages
    );
    Ok(())
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let home = std::env::var_os("COPILOT_HOME")
        .map(std::path::PathBuf::from)
        .ok_or("Set COPILOT_HOME to an absolute private directory")?;
    let client = Client::start(
        ClientOptions::new(copilot_sdk::ClientMode::CodingAgent)
            .with_runtime(RuntimeConnection::InProcess(
                InProcessRuntimeOptions::default()
                    .with_owned(|owned| owned.with_base_directory(&home)),
            ))
            .with_github(|group| group.with_token(""))
            .with_github(|group| group.with_use_logged_in_user(false))
            .with_observability(|group| group.with_disable_client_telemetry(true)),
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
