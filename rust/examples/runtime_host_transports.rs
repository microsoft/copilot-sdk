//! Start JSON-RPC or ACP through an SDK-owned runtime.
//! Run with `--no-default-features --features local-runtime` and a same-checkout `COPILOT_CLI_PATH`.
//! Set `COPILOT_HOME` to a private state directory before running this example.
//! Pass `--acp` for ACP or `--stdio` to serve standard streams until the peer closes input.

use std::net::SocketAddr;

use copilot_sdk::rpc::{
    HostAcpTransportOptions, HostTransportKind, HostTransportProtocol, HostTransportRequest,
    HostTransportStartRequest,
};
use copilot_sdk::{Client, ClientOptions, InProcessRuntimeOptions, RuntimeConnection};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;

async fn demonstrate(
    client: &Client,
    home: &std::path::Path,
) -> Result<(), Box<dyn std::error::Error>> {
    let acp = std::env::args().any(|arg| arg == "--acp");
    let stdio = std::env::args().any(|arg| arg == "--stdio");
    let listener = client
        .rpc()
        .host()
        .transports()
        .start(HostTransportStartRequest {
            protocol: if acp {
                HostTransportProtocol::Acp
            } else {
                HostTransportProtocol::JsonRpc
            },
            transport: if stdio {
                HostTransportKind::Stdio
            } else {
                HostTransportKind::Tcp
            },
            acp: acp.then(|| HostAcpTransportOptions {
                temp_directory: home.to_string_lossy().into_owned(),
                agent_version: env!("CARGO_PKG_VERSION").to_owned(),
                login_command: "copilot".to_owned(),
                login_args: vec!["login".to_owned()],
                session_defaults: None,
            }),
            ..Default::default()
        })
        .await?;
    let request = HostTransportRequest {
        transport_id: listener.transport_id,
    };
    if stdio {
        client
            .rpc()
            .host()
            .transports()
            .wait(request.clone())
            .await?;
    } else {
        let address: SocketAddr = listener
            .address
            .ok_or("TCP listener returned no address")?
            .parse()?;
        if acp {
            let mut socket = BufReader::new(TcpStream::connect(address).await?);
            socket.get_mut().write_all(
                b"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}\n",
            ).await?;
            let mut response = String::new();
            socket.read_line(&mut response).await?;
            let response: serde_json::Value = serde_json::from_str(&response)?;
            if response.get("error").is_some() {
                return Err(format!("ACP initialization failed: {response}").into());
            }
            println!("ACP listener initialized through the SDK-owned runtime");
        } else {
            let peer = Client::start(
                ClientOptions::new(copilot_sdk::ClientMode::CodingAgent).with_runtime(
                    RuntimeConnection::Tcp(copilot_sdk::TcpRuntimeOptions {
                        host: address.ip().to_string(),
                        port: address.port(),
                        connection_token: None,
                    }),
                ),
            )
            .await?;
            let ping = peer.ping(Some("SDK-owned transport")).await?;
            println!("{}", ping.message);
            peer.stop().await?;
        }
    }
    client.rpc().host().transports().stop(request).await?;
    Ok(())
}

#[tokio::main(flavor = "current_thread")]
pub async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let home = std::env::var_os("COPILOT_HOME")
        .map(std::path::PathBuf::from)
        .ok_or("Set COPILOT_HOME to a private state directory")?;
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
