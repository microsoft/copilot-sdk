#[path = "../../examples/runtime_host_transports.rs"]
mod example;

use copilot_sdk::rpc::{
    HostAcpTransportOptions, HostTransportKind, HostTransportProtocol, HostTransportRequest,
    HostTransportStartRequest,
};
use copilot_sdk::{Client, ClientOptions, InProcessRuntimeOptions, RuntimeConnection};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

async fn control_command(
    client: &Client,
    control: &mut TcpStream,
    transport_id: &str,
) -> Result<u8, Box<dyn std::error::Error>> {
    tokio::select! {
        result = async {
            client.rpc().host().transports().wait(HostTransportRequest {
                transport_id: transport_id.to_owned(),
            }).await
        } => {
            result?;
            Err("The standard-stream transport ended before its control command".into())
        },
        command = control.read_u8() => Ok(command?),
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    match std::env::args()
        .skip_while(|arg| arg != "--control-address")
        .nth(1)
    {
        Some(address) => controlled(address),
        None => example::main(),
    }
}

#[tokio::main(flavor = "current_thread")]
async fn controlled(address: String) -> Result<(), Box<dyn std::error::Error>> {
    let home = std::path::PathBuf::from(
        std::env::var_os("COPILOT_HOME").ok_or("COPILOT_HOME is required")?,
    );
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
    let result = async {
        let acp = std::env::args().any(|arg| arg == "--acp");
        let request = HostTransportStartRequest {
            protocol: if acp {
                HostTransportProtocol::Acp
            } else {
                HostTransportProtocol::JsonRpc
            },
            transport: HostTransportKind::Stdio,
            acp: acp.then(|| HostAcpTransportOptions {
                temp_directory: home.to_string_lossy().into_owned(),
                agent_version: "stdio-lifetime-fixture".to_owned(),
                login_command: "copilot".to_owned(),
                login_args: Vec::new(),
                session_defaults: None,
            }),
            ..Default::default()
        };
        let mut transport = client
            .rpc()
            .host()
            .transports()
            .start(request.clone())
            .await?;
        let mut control = TcpStream::connect(address).await?;
        control.write_all(b"R").await?;
        if control_command(&client, &mut control, &transport.transport_id).await? != b'R' {
            return Err("Expected the restart command".into());
        }
        let stopped = client
            .rpc()
            .host()
            .transports()
            .stop(HostTransportRequest {
                transport_id: transport.transport_id,
            })
            .await;
        if std::env::args().any(|arg| arg == "--blocked-output" || arg == "--incomplete-input") {
            let restarted = client.rpc().host().transports().start(request).await;
            let report = serde_json::json!({
                "stopError": stopped.as_ref().err().map(ToString::to_string),
                "restartError": restarted.as_ref().err().map(ToString::to_string),
            });
            control.write_all(format!("{report}\n").as_bytes()).await?;
            if control.read_u8().await? != b'Q' {
                return Err("Expected the shutdown command".into());
            }
            if let Ok(restarted) = restarted {
                client
                    .rpc()
                    .host()
                    .transports()
                    .stop(HostTransportRequest {
                        transport_id: restarted.transport_id,
                    })
                    .await?;
            }
            return Ok(());
        }
        stopped?;
        transport = client.rpc().host().transports().start(request).await?;
        control.write_all(b"R").await?;
        if control_command(&client, &mut control, &transport.transport_id).await? != b'Q' {
            return Err("Expected the shutdown command".into());
        }
        client
            .rpc()
            .host()
            .transports()
            .stop(HostTransportRequest {
                transport_id: transport.transport_id,
            })
            .await?;
        Ok::<(), Box<dyn std::error::Error>>(())
    }
    .await;
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
