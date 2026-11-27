use std::collections::HashMap;
use std::net::SocketAddr;

use copilot_sdk::rpc::{
    HostAcpTransportOptions, HostTransportKind, HostTransportProtocol, HostTransportRequest,
    HostTransportStartRequest, SessionImportRequest, SessionsSaveRequest,
};
use copilot_sdk::{
    Client, ClientOptions, InProcessRuntimeOptions, ResumeSessionOptions, RuntimeConnection,
    RuntimeHostConfiguration,
};
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};

use super::support::{
    DEFAULT_TEST_TOKEN, E2eContext, default_event_timeout, with_dedicated_e2e_context_no_snapshot,
};

#[track_caller]
fn initialize_hook_repository(ctx: &E2eContext) {
    // Filesystem-based discovery must not inherit the enclosing checkout's hook root.
    let mut command = std::process::Command::new("git");
    command
        .args(["init", "--quiet"])
        .current_dir(ctx.work_dir());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    let output = command.output().unwrap();
    assert!(
        output.status.success(),
        "git init failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
}

fn jsonrpc_listener() -> HostTransportStartRequest {
    HostTransportStartRequest {
        protocol: HostTransportProtocol::JsonRpc,
        transport: HostTransportKind::Tcp,
        connection_token: Some("sdk-host-transport-token".to_owned()),
        ..Default::default()
    }
}

fn peer(address: SocketAddr, token: &str) -> ClientOptions {
    ClientOptions::new(copilot_sdk::ClientMode::CodingAgent).with_runtime(RuntimeConnection::Tcp(
        copilot_sdk::TcpRuntimeOptions {
            host: address.ip().to_string(),
            port: address.port(),
            connection_token: Some(token.to_owned()),
        },
    ))
}

async fn acp_request<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    reader: &mut BufReader<R>,
    writer: &mut W,
    id: u64,
    method: &str,
    params: Value,
) -> (Value, Vec<Value>) {
    let (response, updates) = acp_response(reader, writer, id, method, params).await;
    assert!(response.get("error").is_none(), "{method}: {response}");
    (response["result"].clone(), updates)
}

async fn acp_response<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    reader: &mut BufReader<R>,
    writer: &mut W,
    id: u64,
    method: &str,
    params: Value,
) -> (Value, Vec<Value>) {
    let mut request = serde_json::to_vec(&json!({
        "jsonrpc": "2.0", "id": id, "method": method, "params": params,
    }))
    .unwrap();
    request.push(b'\n');
    writer.write_all(&request).await.unwrap();
    let mut updates = Vec::new();
    loop {
        let mut line = String::new();
        assert_ne!(
            reader.read_line(&mut line).await.unwrap(),
            0,
            "ACP closed before {method}"
        );
        let response: Value = serde_json::from_str(&line).unwrap();
        if response["id"] == id {
            return (response, updates);
        }
        if response["method"] == "session/update" {
            updates.push(response["params"].clone());
        }
    }
}

fn acp_defaults() -> Value {
    json!({
        "model": "claude-sonnet-4.5",
        "enableConfigDiscovery": false,
        "github": { "fetchManagedSettings": false },
        "extensions": { "enabled": false },
    })
}

async fn connect_acp(
    owner: &Client,
    ctx: &E2eContext,
    defaults: Value,
) -> (
    String,
    BufReader<tokio::net::tcp::OwnedReadHalf>,
    tokio::net::tcp::OwnedWriteHalf,
) {
    connect_acp_with_capabilities(owner, ctx, defaults, json!({})).await
}

async fn connect_acp_with_capabilities(
    owner: &Client,
    ctx: &E2eContext,
    defaults: Value,
    capabilities: Value,
) -> (
    String,
    BufReader<tokio::net::tcp::OwnedReadHalf>,
    tokio::net::tcp::OwnedWriteHalf,
) {
    let transport = owner
        .rpc()
        .host()
        .transports()
        .start(HostTransportStartRequest {
            protocol: HostTransportProtocol::Acp,
            transport: HostTransportKind::Tcp,
            acp: Some(HostAcpTransportOptions {
                temp_directory: ctx.work_dir().to_string_lossy().into_owned(),
                agent_version: "sdk-acp-lifecycle".to_owned(),
                login_command: "copilot".to_owned(),
                login_args: vec!["login".to_owned()],
                session_defaults: Some(serde_json::from_value(defaults).unwrap()),
            }),
            ..Default::default()
        })
        .await
        .unwrap();
    let (reader, mut writer) = TcpStream::connect(transport.address.unwrap())
        .await
        .unwrap()
        .into_split();
    let mut reader = BufReader::new(reader);
    acp_request(
        &mut reader,
        &mut writer,
        1,
        "initialize",
        json!({ "protocolVersion": 1, "clientCapabilities": capabilities }),
    )
    .await;
    (transport.transport_id, reader, writer)
}

#[tokio::test]
async fn acp_negotiated_form_support_survives_sdk_create_and_cold_load() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            scripted_acp(
                ctx,
                json!(vec![
                    json!({
                        "requestIncludes": "ACP_CAPABILITIES_PROMPT",
                        "content": "ACP_CAPABILITIES_DONE",
                    });
                    4
                ]),
            );
            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::default(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            )
            .await
            .unwrap();
            let mut observed = Vec::new();
            for form in [true, false] {
                let capabilities = if form {
                    json!({ "elicitation": { "form": {} } })
                } else {
                    json!({ "elicitation": { "url": {} } })
                };
                let (transport_id, mut reader, mut writer) =
                    connect_acp_with_capabilities(&owner, ctx, acp_defaults(), capabilities).await;
                let (created, _) = acp_request(
                    &mut reader,
                    &mut writer,
                    2,
                    "session/new",
                    json!({ "cwd": ctx.work_dir(), "mcpServers": [] }),
                )
                .await;
                for phase in ["create", "cold-load"] {
                    if phase == "cold-load" {
                        acp_request(
                            &mut reader,
                            &mut writer,
                            3,
                            "session/load",
                            json!({
                                "sessionId": created["sessionId"],
                                "cwd": ctx.work_dir(), "mcpServers": [],
                            }),
                        )
                        .await;
                    }
                    let before = ctx.exchanges().len();
                    let (completed, _) = acp_request(
                        &mut reader,
                        &mut writer,
                        4,
                        "session/prompt",
                        json!({
                            "sessionId": created["sessionId"],
                            "prompt": [{ "type": "text", "text": "ACP_CAPABILITIES_PROMPT" }],
                        }),
                    )
                    .await;
                    let requests = ctx.exchanges();
                    let tools = requests[before..]
                        .iter()
                        .filter_map(|exchange| {
                            exchange.pointer("/request/tools").and_then(Value::as_array)
                        })
                        .collect::<Vec<_>>();
                    observed.push((
                        form,
                        phase,
                        completed,
                        tools
                            .iter()
                            .map(|tools| {
                                tools.iter().any(|tool| {
                                    tool.pointer("/function/name") == Some(&json!("ask_user"))
                                })
                            })
                            .collect::<Vec<_>>(),
                    ));
                    acp_request(
                        &mut reader,
                        &mut writer,
                        5,
                        "session/close",
                        json!({ "sessionId": created["sessionId"] }),
                    )
                    .await;
                }
                owner
                    .rpc()
                    .host()
                    .transports()
                    .stop(HostTransportRequest { transport_id })
                    .await
                    .unwrap();
            }
            owner.stop().await.unwrap();
            for (form, phase, completed, offered) in observed {
                assert_eq!(completed["stopReason"], "end_turn", "{completed}");
                assert!(
                    !offered.is_empty(),
                    "no model request for form={form}, {phase}"
                );
                assert!(
                    offered.iter().all(|offered| *offered == form),
                    "ask_user availability lost negotiation for form={form}, {phase}: {offered:?}"
                );
            }
        })
    })
    .await;
}

fn scripted_acp(ctx: &E2eContext, replies: Value) {
    ctx.set_default_copilot_user();
    ctx.configure_scripted_replay(
        replies,
        json!({
            "models": ["claude-sonnet-4.5"],
            "modelCatalog": {
                "claude-sonnet-4.5": { "supported_endpoints": ["/v1/messages"] },
            },
            "conversations": [],
        }),
    );
}

fn imported_conversation(ctx: &E2eContext) -> SessionImportRequest {
    SessionImportRequest {
        input_path: ctx.work_dir().join("history.jsonl").to_string_lossy().into_owned(),
        jsonl: [
            json!({
                "type": "session", "version": 1, "externalId": "sdk-host-import",
                "createdAt": "2024-02-29T12:34:56Z",
            }),
            json!({
                "type": "message", "id": "historical-user", "timestamp": "2024-02-29T12:34:56Z",
                "role": "user", "content": [{ "type": "text", "text": "Remember the imported conversation." }],
            }),
        ].iter().map(ToString::to_string).collect::<Vec<_>>().join("\n"),
        default_working_directory: ctx.work_dir().to_string_lossy().into_owned(),
        working_directory: Some(ctx.work_dir().to_string_lossy().into_owned()),
        name: None,
        dry_run: None,
    }
}

#[tokio::test]
async fn imported_session_retains_required_host_policy_on_raw_attach() {
    use std::panic::AssertUnwindSafe;

    use futures_util::FutureExt;

    for provider_backed in [false, true] {
        with_dedicated_e2e_context_no_snapshot(move |ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            ctx.configure_network_fixture(&json!({
                    "managedSettings": {
                        "status": 200,
                        "body": { "permissions": { "disableBypassPermissionsMode": "disable" } },
                    },
                })).unwrap();
            let harness = ctx.repo_root().join("test/harness");
            let configuration = RuntimeHostConfiguration {
                        require_managed_settings: Some(true),
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
            )).with_github(|group| group.with_token(DEFAULT_TEST_TOKEN));
            let mut resolved_provider = None;
            if provider_backed {
                let provider = std::sync::Arc::new(HeldImportProvider {
                    root: ctx.work_dir().join("import-provider"),
                    state_path: "/session-state".to_owned(),
                    entered: tokio::sync::Notify::new(),
                    release: tokio::sync::watch::channel(true).0,
                    hold_mkdir: true,
                    hold_append: std::sync::atomic::AtomicBool::new(false),
                    stop_on_read: std::sync::Mutex::new(None),
                });
                std::fs::create_dir_all(provider.path("/workspace")).unwrap();
                options = options
                    .with_session_fs(copilot_sdk::SessionFsConfig::new(
                        "/workspace", provider.state_path.clone(), copilot_sdk::SessionFsConventions::Posix,
                    ));
                resolved_provider = Some(provider);
            }
            let owner = Client::start_with_host_configuration(options, configuration).await.unwrap();
            if let Some(provider) = resolved_provider {
                owner.register_session_fs_provider_resolver(std::sync::Arc::new(
                    move |_: &str| -> copilot_sdk::Result<std::sync::Arc<dyn copilot_sdk::SessionFsProvider>> {
                        Ok(provider.clone())
                    },
                )).unwrap();
            }
            let outcome = AssertUnwindSafe(async {
                let mut request = imported_conversation(ctx);
                if provider_backed {
                    request.working_directory = Some("/workspace".to_owned());
                }
                let imported = owner.rpc().sessions().import(request).await.unwrap();
                assert!(imported.ok, "{imported:?}");
                let id = imported.session_id.expect("imported session id");
                let attached = owner.call("sessions.open", Some(json!({
                    "kind": "attach", "sessionId": id,
                }))).await.unwrap();
                assert_eq!(attached["status"], "resumed", "{attached}");
                owner.call("session.managedSettings.awaitApplied", Some(json!({
                    "sessionId": id,
                }))).await.unwrap();
                let policy = owner.call("session.managedSettings.get", Some(json!({
                    "sessionId": id,
                }))).await.unwrap();
                let permission = owner.call("session.permissions.setMode", Some(json!({
                    "sessionId": id, "mode": "allow-all",
                }))).await.unwrap();
                assert!(
                    policy["bypassPermissionsDisabled"] == true
                        && permission["success"] == false
                        && permission["mode"] == "manual",
                    "import and raw attach lost required host policy: policy={policy}, permission={permission}",
                );
                let servers = owner.call("session.mcp.list", Some(json!({
                    "sessionId": id,
                }))).await.unwrap();
                assert!(
                    servers["servers"].as_array().unwrap().iter()
                        .any(|server| server["name"] == "host-meta"),
                    "import and raw attach lost host MCP defaults (provider={provider_backed}): {servers}",
                );
            }).catch_unwind().await;
            let stopped = owner.stop().await;
            if let Err(panic) = outcome {
                if let Err(error) = stopped {
                    eprintln!("Import policy host cleanup also failed: {error}");
                }
                std::panic::resume_unwind(panic);
            }
            stopped.unwrap();
        })
    }).await;
    }
}

struct ProviderTransportStop {
    owner: Client,
    transport_id: String,
    wait: bool,
    receipt: tokio::sync::oneshot::Sender<copilot_sdk::Result<()>>,
}

struct HeldImportProvider {
    root: std::path::PathBuf,
    state_path: String,
    entered: tokio::sync::Notify,
    release: tokio::sync::watch::Sender<bool>,
    hold_mkdir: bool,
    hold_append: std::sync::atomic::AtomicBool,
    stop_on_read: std::sync::Mutex<Option<ProviderTransportStop>>,
}

impl HeldImportProvider {
    fn path(&self, path: &str) -> std::path::PathBuf {
        self.root.join(path.trim_start_matches('/'))
    }
}

#[async_trait::async_trait]
impl copilot_sdk::SessionFsProvider for HeldImportProvider {
    async fn read_file(&self, path: &str) -> Result<String, copilot_sdk::session_fs::FsError> {
        let stopping = if path.ends_with("/events.jsonl") {
            self.stop_on_read.lock().unwrap().take()
        } else {
            None
        };
        if let Some(ProviderTransportStop {
            owner,
            transport_id,
            wait,
            receipt,
        }) = stopping
        {
            let mut released = self.release.subscribe();
            let transports = owner.rpc().host().transports();
            let finish = async {
                let request = HostTransportRequest { transport_id };
                if wait {
                    transports.wait(request).await.map(|_| ())
                } else {
                    transports.stop(request).await.map(|_| ())
                }
            };
            let result = tokio::select! {
                result = finish => Some(result),
                _ = released.wait_for(|released| *released) => None,
            };
            if let Some(result) = result {
                let _ = receipt.send(result);
            }
        }
        tokio::fs::read_to_string(self.path(path))
            .await
            .map_err(Into::into)
    }

    async fn write_file(
        &self,
        path: &str,
        content: &str,
        _mode: Option<i64>,
    ) -> Result<(), copilot_sdk::session_fs::FsError> {
        let target = self.path(path);
        tokio::fs::create_dir_all(target.parent().unwrap()).await?;
        tokio::fs::write(target, content).await.map_err(Into::into)
    }

    async fn append_file(
        &self,
        path: &str,
        content: &str,
        _mode: Option<i64>,
    ) -> Result<(), copilot_sdk::session_fs::FsError> {
        if path.strip_prefix(&self.state_path) == Some("/events.jsonl")
            && self
                .hold_append
                .swap(false, std::sync::atomic::Ordering::AcqRel)
        {
            let mut released = self.release.subscribe();
            self.entered.notify_one();
            released.wait_for(|released| *released).await.unwrap();
        }
        let target = self.path(path);
        tokio::fs::create_dir_all(target.parent().unwrap()).await?;
        let mut file = tokio::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(target)
            .await?;
        file.write_all(content.as_bytes()).await.map_err(Into::into)
    }

    async fn exists(&self, path: &str) -> Result<bool, copilot_sdk::session_fs::FsError> {
        tokio::fs::try_exists(self.path(path))
            .await
            .map_err(Into::into)
    }

    async fn stat(
        &self,
        path: &str,
    ) -> Result<copilot_sdk::FileInfo, copilot_sdk::session_fs::FsError> {
        let metadata = tokio::fs::metadata(self.path(path)).await?;
        Ok(copilot_sdk::FileInfo::new(
            metadata.is_file(),
            metadata.is_dir(),
            metadata.len() as i64,
            "1970-01-01T00:00:00Z",
            "1970-01-01T00:00:00Z",
        ))
    }

    async fn mkdir(
        &self,
        path: &str,
        recursive: bool,
        _mode: Option<i64>,
    ) -> Result<(), copilot_sdk::session_fs::FsError> {
        // Imports and forks claim this directory before they write history.
        if self.hold_mkdir && path == self.state_path {
            let mut released = self.release.subscribe();
            self.entered.notify_one();
            released.wait_for(|released| *released).await.unwrap();
        }
        if recursive {
            tokio::fs::create_dir_all(self.path(path)).await
        } else {
            tokio::fs::create_dir(self.path(path)).await
        }
        .map_err(Into::into)
    }

    async fn readdir(&self, path: &str) -> Result<Vec<String>, copilot_sdk::session_fs::FsError> {
        let mut directory = tokio::fs::read_dir(self.path(path)).await?;
        let mut entries = Vec::new();
        while let Some(entry) = directory.next_entry().await? {
            entries.push(entry.file_name().to_string_lossy().into_owned());
        }
        Ok(entries)
    }

    async fn readdir_with_types(
        &self,
        path: &str,
    ) -> Result<Vec<copilot_sdk::DirEntry>, copilot_sdk::session_fs::FsError> {
        let mut directory = tokio::fs::read_dir(self.path(path)).await?;
        let mut entries = Vec::new();
        while let Some(entry) = directory.next_entry().await? {
            let kind = if entry.file_type().await?.is_dir() {
                copilot_sdk::DirEntryKind::Directory
            } else {
                copilot_sdk::DirEntryKind::File
            };
            entries.push(copilot_sdk::DirEntry::new(
                entry.file_name().to_string_lossy(),
                kind,
            ));
        }
        Ok(entries)
    }

    async fn rm(
        &self,
        path: &str,
        recursive: bool,
        force: bool,
    ) -> Result<(), copilot_sdk::session_fs::FsError> {
        let result = if recursive {
            tokio::fs::remove_dir_all(self.path(path)).await
        } else {
            tokio::fs::remove_file(self.path(path)).await
        };
        match result {
            Err(error) if force && error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            result => result.map_err(Into::into),
        }
    }

    async fn rename(&self, src: &str, dest: &str) -> Result<(), copilot_sdk::session_fs::FsError> {
        let target = self.path(dest);
        tokio::fs::create_dir_all(target.parent().unwrap()).await?;
        tokio::fs::rename(self.path(src), target)
            .await
            .map_err(Into::into)
    }
}

#[tokio::test]
async fn acp_logout_rejects_import_pending_provider_claim() {
    use std::panic::AssertUnwindSafe;
    use std::sync::Arc;

    use futures_util::FutureExt;

    const CHILD: &str = "COPILOT_SDK_TEST_ACP_IMPORT_LOGOUT_CHILD";
    if std::env::var(CHILD).as_deref() != Ok("1") {
        let home = tempfile::tempdir().unwrap();
        let mut command = tokio::process::Command::new(std::env::current_exe().unwrap());
        command
            .current_dir(std::env::current_dir().unwrap())
            .args([
                "--exact",
                "e2e_cases::host_transports::acp_logout_rejects_import_pending_provider_claim",
                "--nocapture",
            ])
            .env(CHILD, "1")
            .env("COPILOT_DISABLE_KEYTAR", "1")
            .env("HOME", home.path())
            .env("USERPROFILE", home.path())
            .env("GH_CONFIG_DIR", home.path())
            .kill_on_drop(true);
        for key in [
            "COPILOT_GITHUB_TOKEN",
            "GH_TOKEN",
            "GITHUB_TOKEN",
            "GH_ENTERPRISE_TOKEN",
            "GITHUB_ENTERPRISE_TOKEN",
            "CAPI_HMAC_KEY",
            "COPILOT_HMAC_KEY",
        ] {
            command.env_remove(key);
        }
        #[cfg(windows)]
        {
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        let output = command.output().await.unwrap();
        assert!(
            output.status.success() && String::from_utf8_lossy(&output.stdout).contains("1 passed"),
            "isolated import logout case:\n{}\n{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        return;
    }
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let token = "gho_sdk_import_logout_fixture";
            ctx.set_copilot_user_by_token(token);
            std::fs::create_dir_all(ctx.home_dir()).unwrap();
            std::fs::write(ctx.home_dir().join("settings.json"), r#"{"storeTokenPlaintext":true}"#).unwrap();
            let provider = Arc::new(HeldImportProvider {
                root: ctx.work_dir().join("import-provider"),
                state_path: "/session-state".to_owned(),
                entered: tokio::sync::Notify::new(),
                release: tokio::sync::watch::channel(false).0,
                hold_mkdir: true,
                hold_append: std::sync::atomic::AtomicBool::new(false),
                stop_on_read: std::sync::Mutex::new(None),
            });
            std::fs::create_dir_all(provider.path("/workspace")).unwrap();
            let save_id = uuid::Uuid::new_v4().to_string();
            let save_provider = Arc::new(HeldImportProvider {
                root: ctx.work_dir().join("save-provider"),
                state_path: provider.state_path.clone(),
                entered: tokio::sync::Notify::new(),
                release: tokio::sync::watch::channel(false).0,
                hold_mkdir: false,
                hold_append: std::sync::atomic::AtomicBool::new(false),
                stop_on_read: std::sync::Mutex::new(None),
            });
            std::fs::create_dir_all(save_provider.path("/workspace")).unwrap();
            let fork_provider = Arc::new(HeldImportProvider {
                root: ctx.work_dir().join("fork-provider"),
                state_path: provider.state_path.clone(),
                entered: tokio::sync::Notify::new(),
                release: tokio::sync::watch::channel(false).0,
                hold_mkdir: true,
                hold_append: std::sync::atomic::AtomicBool::new(false),
                stop_on_read: std::sync::Mutex::new(None),
            });
            let resolved = provider.clone();
            let resolved_save_id = save_id.clone();
            let resolved_save = save_provider.clone();
            let resolved_fork = fork_provider.clone();
            let fork_destination_id = Arc::new(std::sync::Mutex::new(None::<String>));
            let resolved_fork_id = fork_destination_id.clone();
            let source_id = std::sync::Mutex::new(None::<String>);
            let owner = Client::start_with_host_configuration(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::new(),
                ))
                .with_github(|group| group.with_use_logged_in_user(true))
                .with_session_fs(copilot_sdk::SessionFsConfig::new(
                    "/", provider.state_path.clone(), copilot_sdk::SessionFsConventions::Posix,
                )),
                RuntimeHostConfiguration {
                    persist_unnamed_sessions: Some(false),
                    ..Default::default()
                },
            ).await.unwrap();
            owner.register_session_fs_provider_resolver(Arc::new(
                move |id: &str| -> copilot_sdk::Result<Arc<dyn copilot_sdk::SessionFsProvider>> {
                    if id == resolved_save_id {
                        return Ok(resolved_save.clone());
                    }
                    if source_id.lock().unwrap().get_or_insert_with(|| id.to_owned()) == id {
                        return Ok(resolved.clone());
                    }
                    assert_eq!(resolved_fork_id.lock().unwrap().get_or_insert_with(|| id.to_owned()), id);
                    Ok(resolved_fork.clone())
                },
            )).unwrap();
            let outcome = AssertUnwindSafe(async {
                let logged_in = owner.call("account.login", Some(json!({
                    "host": "https://github.com", "token": token,
                }))).await.unwrap();
                assert_eq!(logged_in["storedInVault"], true, "{logged_in}");
                let (transport_id, mut reader, mut writer) = connect_acp(&owner, ctx, acp_defaults()).await;
                let sessions = owner.rpc().sessions();
                let mut request = imported_conversation(ctx);
                request.working_directory = Some("/workspace".to_owned());
                let importing = sessions.import(request);
                tokio::pin!(importing);
                tokio::select! {
                    () = provider.entered.notified() => {}
                    result = &mut importing => panic!("import finished before its provider claim: {result:?}"),
                }
                let busy_logout = tokio::time::timeout(
                    default_event_timeout(),
                    acp_response(&mut reader, &mut writer, 2, "auth/logout", json!({})),
                ).await;
                provider.release.send_replace(true);
                let imported = importing.await.unwrap();
                assert!(imported.ok, "{imported:?}");
                let id = imported.session_id.expect("imported session id");
                let (busy_logout, _) = busy_logout.expect("logout waited for an admitted import callback");
                assert!(
                    busy_logout["error"]["message"].as_str()
                        .is_some_and(|message| message.contains("Host authentication is busy")),
                    "logout did not reject concurrent import admission: {busy_logout}",
                );
                let attached = owner.call("sessions.open", Some(json!({
                    "kind": "attach", "sessionId": id,
                }))).await.unwrap();
                assert_eq!(attached["status"], "resumed", "{attached}");
                let before = owner.call("session.gitHubAuth.getCurrentAuthInfo", Some(json!({
                    "sessionId": id,
                }))).await.unwrap();
                assert_eq!(before["login"], "rust-e2e-user", "{before}");
                let save_session = owner.create_session(
                    copilot_sdk::CreateSessionOptions::default()
                        .with_session_id(save_id.clone())
                        .with_state(|group| group.with_session_fs_provider(save_provider.clone()))
                        .with_working_directory("/workspace")
                        .with_model("claude-sonnet-4.5")
                        .with_enable_config_discovery(false)
                        .with_github(|group| group.with_fetch_managed_settings(false))
                        .with_extensions(|group| group.with_enabled(false))
                        .with_permissions(|group| group.with_on_request(Arc::new(copilot_sdk::handler::ApproveAllHandler))),
                ).await.unwrap();
                // An unnamed session has no background event persistence before its first forced save.
                assert!(!save_provider.path("/session-state/events.jsonl").exists());
                save_provider.hold_append.store(true, std::sync::atomic::Ordering::Release);
                for (method, session_id, held_provider, request_id, params) in [
                    ("sessions.save", save_id.as_str(), &save_provider, 3, json!({ "sessionId": save_id, "force": true })),
                    // The destination claim belongs to the fork, unlike a source writer's append.
                    ("sessions.fork", id.as_str(), &fork_provider, 4, json!({ "sessionId": id })),
                ] {
                    let saving = owner.call(method, Some(params));
                    tokio::pin!(saving);
                    let busy_logout = tokio::time::timeout(default_event_timeout(), async {
                        tokio::select! {
                            biased;
                            result = &mut saving => panic!("{method} ended before its provider callback: {result:?}"),
                            () = held_provider.entered.notified() => {}
                        }
                        acp_response(&mut reader, &mut writer, request_id, "auth/logout", json!({})).await
                    }).await;
                    held_provider.release.send_replace(true);
                    let saved = saving.await;
                    let (busy_logout, _) = busy_logout.unwrap_or_else(|_| panic!("logout waited for an admitted {method} callback"));
                    let saved = saved.unwrap();
                    if method == "sessions.fork" {
                        assert_eq!(saved["sessionId"].as_str(), fork_destination_id.lock().unwrap().as_deref());
                    }
                    assert!(
                        held_provider.path("/session-state/events.jsonl").is_file(),
                        "{method} did not persist history through its held provider",
                    );
                    assert!(
                        busy_logout["error"]["message"].as_str()
                            .is_some_and(|message| message.contains("Host authentication is busy")),
                        "logout did not reject concurrent {method} admission: {busy_logout}",
                    );
                    let preserved = owner.call("session.gitHubAuth.getCurrentAuthInfo", Some(json!({
                        "sessionId": session_id,
                    }))).await.unwrap();
                    assert_eq!(preserved["login"], "rust-e2e-user", "{preserved}");
                    let account = owner.call("account.getCurrentAuth", Some(json!({}))).await.unwrap();
                    assert_eq!(account["authInfo"]["login"], "rust-e2e-user");
                }
                acp_request(&mut reader, &mut writer, 5, "auth/logout", json!({})).await;
                for session_id in [id.as_str(), save_id.as_str()] {
                    let after = owner.call("session.gitHubAuth.getCurrentAuthInfo", Some(json!({
                        "sessionId": session_id,
                    }))).await.unwrap();
                    assert!(after.is_null(), "logout left stale session authentication: {after}");
                }
                drop(save_session);
                owner.rpc().host().transports().stop(HostTransportRequest { transport_id }).await.unwrap();
            }).catch_unwind().await;
            provider.release.send_replace(true);
            save_provider.release.send_replace(true);
            fork_provider.release.send_replace(true);
            let stopped = owner.stop().await;
            if let Err(panic) = outcome {
                if let Err(error) = stopped {
                    eprintln!("Import logout host cleanup also failed: {error}");
                }
                std::panic::resume_unwind(panic);
            }
            stopped.unwrap();
        })
    }).await;
}

#[tokio::test]
async fn transport_finish_rejects_its_owners_pending_provider_callback() {
    use std::panic::AssertUnwindSafe;
    use std::sync::Arc;

    use futures_util::FutureExt;

    for wait in [false, true] {
        with_dedicated_e2e_context_no_snapshot(|ctx| {
            Box::pin(async move {
                let provider = Arc::new(HeldImportProvider {
                    root: ctx.work_dir().join("transport-provider"),
                    state_path: "/session-state".to_owned(),
                    entered: tokio::sync::Notify::new(),
                    release: tokio::sync::watch::channel(true).0,
                    hold_mkdir: true,
                    hold_append: std::sync::atomic::AtomicBool::new(false),
                    stop_on_read: std::sync::Mutex::new(None),
                });
                std::fs::create_dir_all(provider.path("/workspace")).unwrap();
                let resolved = provider.clone();
                let owner = Client::start(
                    ctx.client_options_with_transport(RuntimeConnection::InProcess(
                        InProcessRuntimeOptions::default(),
                    ))
                    .with_session_fs(copilot_sdk::SessionFsConfig::new(
                        "/",
                        provider.state_path.clone(),
                        copilot_sdk::SessionFsConventions::Posix,
                    )),
                )
                .await
                .unwrap();
                owner
                    .register_session_fs_provider_resolver(
                        Arc::new(
                            move |_: &str| -> copilot_sdk::Result<
                                Arc<dyn copilot_sdk::SessionFsProvider>,
                            > { Ok(resolved.clone()) },
                        ),
                    )
                    .unwrap();
                let outcome = AssertUnwindSafe(async {
                    let mut request = imported_conversation(ctx);
                    request.working_directory = Some("/workspace".to_owned());
                    let imported = owner.rpc().sessions().import(request).await.unwrap();
                    assert!(imported.ok, "{imported:?}");
                    let id = imported.session_id.unwrap();
                    owner
                        .call(
                            "sessions.open",
                            Some(json!({
                                "kind": "attach", "sessionId": id,
                            })),
                        )
                        .await
                        .unwrap();
                    let transport = owner
                        .rpc()
                        .host()
                        .transports()
                        .start(jsonrpc_listener())
                        .await
                        .unwrap();
                    let remote = Client::start(peer(
                        transport.address.unwrap().parse().unwrap(),
                        "sdk-host-transport-token",
                    ))
                    .await
                    .unwrap();
                    let (send, receipt) = tokio::sync::oneshot::channel();
                    provider.release.send_replace(false);
                    *provider.stop_on_read.lock().unwrap() = Some(ProviderTransportStop {
                        owner: owner.clone(),
                        transport_id: transport.transport_id.clone(),
                        wait,
                        receipt: send,
                    });
                    let caller = remote.clone();
                    let forking = tokio::spawn(async move {
                        caller
                            .call(
                                "sessions.fork",
                                Some(json!({
                                    "sessionId": id, "toEventId": "missing-event",
                                })),
                            )
                            .await
                    });
                    let stopped = tokio::time::timeout(default_event_timeout(), receipt).await;
                    provider.release.send_replace(true);
                    let forked = forking.await.unwrap();
                    let remote_stopped = remote.stop().await;
                    let stop = stopped
                        .expect("transport finish waited for its own pending provider callback")
                        .expect("provider dropped its stop receipt");
                    let error =
                        stop.expect_err("reentrant transport finish must reject before its drain");
                    assert!(error.to_string().contains("pending callbacks"), "{error}");
                    assert!(
                        forked
                            .unwrap_err()
                            .to_string()
                            .contains("Event missing-event not found")
                    );
                    remote_stopped.unwrap();
                    owner
                        .rpc()
                        .host()
                        .transports()
                        .stop(HostTransportRequest {
                            transport_id: transport.transport_id,
                        })
                        .await
                        .unwrap();
                    assert_eq!(
                        owner.ping(Some("owner survives")).await.unwrap().message,
                        "pong: owner survives"
                    );
                })
                .catch_unwind()
                .await;
                provider.release.send_replace(true);
                provider.stop_on_read.lock().unwrap().take();
                let stopped = owner.stop().await;
                if let Err(panic) = outcome {
                    if let Err(error) = stopped {
                        eprintln!("Provider transport host cleanup also failed: {error}");
                    }
                    std::panic::resume_unwind(panic);
                }
                stopped.unwrap();
            })
        })
        .await;
    }
}

fn transport_fixture(ctx: &E2eContext) -> tokio::process::Command {
    let fixture = std::path::Path::new(env!("CARGO_BIN_EXE_copilot-host-transports-fixture"))
        .canonicalize()
        .expect("resolve the checked-in transport fixture");
    let mut command = tokio::process::Command::new(fixture);
    command
        .current_dir(ctx.work_dir())
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .envs(ctx.environment())
        .env("COPILOT_GITHUB_TELEMETRY_DISABLED", "true")
        .env("COPILOT_TELEMETRY_DISABLED", "true")
        .env("DO_NOT_TRACK", "1")
        .kill_on_drop(true);
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}

#[cfg(unix)]
fn nonblocking_stdio(
    command: &mut tokio::process::Command,
) -> (tokio::net::UnixStream, tokio::net::UnixStream) {
    use std::os::fd::OwnedFd;
    use std::os::unix::net::UnixStream;
    let (host_input, input) = UnixStream::pair().unwrap();
    let (host_output, output) = UnixStream::pair().unwrap();
    for socket in [&host_input, &host_output, &input, &output] {
        socket.set_nonblocking(true).unwrap();
    }
    command
        .stdin(std::process::Stdio::from(OwnedFd::from(host_input)))
        .stdout(std::process::Stdio::from(OwnedFd::from(host_output)));
    (
        tokio::net::UnixStream::from_std(input).unwrap(),
        tokio::net::UnixStream::from_std(output).unwrap(),
    )
}

#[tokio::test]
async fn owned_acp_refuses_non_loopback_before_binding() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let owner = Client::start(ctx.client_options_with_transport(
                RuntimeConnection::InProcess(InProcessRuntimeOptions::default()),
            ))
            .await
            .unwrap();
            // Occupy the port so the broken path cannot expose an unauthenticated listener.
            let occupied = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let result = owner
                .rpc()
                .host()
                .transports()
                .start(HostTransportStartRequest {
                    protocol: HostTransportProtocol::Acp,
                    transport: HostTransportKind::Tcp,
                    hostname: Some("0.0.0.0".to_owned()),
                    port: Some(occupied.local_addr().unwrap().port().into()),
                    acp: Some(HostAcpTransportOptions {
                        temp_directory: ctx.work_dir().to_string_lossy().into_owned(),
                        agent_version: "sdk-bind-policy".to_owned(),
                        login_command: "copilot".to_owned(),
                        login_args: vec!["login".to_owned()],
                        session_defaults: None,
                    }),
                    ..Default::default()
                })
                .await;
            owner.stop().await.unwrap();
            let error = result.expect_err("ACP must refuse a non-loopback listener");
            assert!(
                error
                    .to_string()
                    .contains("refusing to bind the ACP server to non-loopback host"),
                "ACP must enforce its policy before attempting to bind the occupied port: {error}"
            );
        })
    })
    .await;
}

#[tokio::test]
async fn owned_host_serves_protocols_without_replacing_its_engine() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            let token = DEFAULT_TEST_TOKEN;
            ctx.configure_network_fixture(&json!({
                "managedSettings": {
                    "status": 200,
                    "body": { "permissions": { "disableBypassPermissionsMode": "disable" } },
                },
            }))
            .unwrap();
            let options = ctx
                .client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::new(),
                ))
                .with_github(|group| group.with_token(token));
            let owner = Client::start_with_host_configuration(
                options,
                RuntimeHostConfiguration {
                    require_managed_settings: Some(true),
                    ..Default::default()
                },
            )
            .await
            .expect("start SDK-owned host");
            let refused = owner
                .call("cafeMetrics.start", Some(json!({})))
                .await
                .expect_err("transport ownership must not grant CLI-only authority");
            assert!(
                refused.to_string().contains("trusted CLI host"),
                "{refused}"
            );
            let session = owner
                .create_session(
                    ctx.approve_all_session_config()
                        .with_github(|group| group.with_token(token))
                        .with_enable_config_discovery(false)
                        .with_github(|group| group.with_fetch_managed_settings(false))
                        .with_name("Transport owner"),
                )
                .await
                .unwrap();
            session
                .rpc()
                .managed_settings()
                .await_applied()
                .await
                .unwrap();
            assert!(
                session
                    .rpc()
                    .managed_settings()
                    .get()
                    .await
                    .unwrap()
                    .bypass_permissions_disabled
            );
            let untrusted = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::SpawnedStdio(
                    Default::default(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            )
            .await
            .unwrap();
            let refused = untrusted
                .rpc()
                .host()
                .transports()
                .start(jsonrpc_listener())
                .await
                .unwrap_err();
            assert!(refused.to_string().contains("controller"), "{refused}");
            untrusted.stop().await.unwrap();
            let refused = owner
                .rpc()
                .host()
                .transports()
                .start(HostTransportStartRequest {
                    hostname: Some("0.0.0.0".to_owned()),
                    connection_token: None,
                    ..jsonrpc_listener()
                })
                .await
                .unwrap_err();
            assert!(
                refused.to_string().contains("connection token"),
                "{refused}"
            );
            let listener = owner
                .rpc()
                .host()
                .transports()
                .start(jsonrpc_listener())
                .await
                .unwrap();
            let address: SocketAddr = listener.address.as_ref().unwrap().parse().unwrap();
            Client::start(peer(address, "wrong-token"))
                .await
                .expect_err("the public listener must enforce its connection token");
            let observer = Client::start(peer(address, "sdk-host-transport-token"))
                .await
                .unwrap();
            let refused = observer
                .rpc()
                .host()
                .transports()
                .start(jsonrpc_listener())
                .await
                .unwrap_err();
            assert!(refused.to_string().contains("controller"), "{refused}");
            let observed = observer
                .resume_session(
                    ResumeSessionOptions::new(session.id().clone())
                        .with_permissions(|group| {
                            group.with_on_request(std::sync::Arc::new(
                                copilot_sdk::handler::ApproveAllHandler,
                            ))
                        })
                        .with_enable_config_discovery(false),
                )
                .await
                .unwrap();
            assert_eq!(observed.id(), session.id());
            assert_eq!(
                observed
                    .rpc()
                    .metadata()
                    .snapshot()
                    .await
                    .unwrap()
                    .working_directory,
                session
                    .rpc()
                    .metadata()
                    .snapshot()
                    .await
                    .unwrap()
                    .working_directory
            );
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest {
                    transport_id: listener.transport_id,
                })
                .await
                .unwrap();
            assert!(
                observer.ping(None).await.is_err(),
                "stopped transport must close existing peers"
            );
            observer.stop().await.unwrap();
            session
                .get_events()
                .await
                .expect("stopping an observer transport must preserve its owner");

            let acp = owner
                .rpc()
                .host()
                .transports()
                .start(HostTransportStartRequest {
                    protocol: HostTransportProtocol::Acp,
                    transport: HostTransportKind::Tcp,
                    acp: Some(HostAcpTransportOptions {
                        temp_directory: ctx.work_dir().to_string_lossy().into_owned(),
                        agent_version: "sdk-transport-example".to_owned(),
                        login_command: "copilot".to_owned(),
                        login_args: vec!["login".to_owned()],
                        session_defaults: Some(
                            serde_json::from_value(json!({
                                "enableConfigDiscovery": false,
                                "github": { "fetchManagedSettings": false },
                                "extensions": { "enabled": false },
                            }))
                            .unwrap(),
                        ),
                    }),
                    ..Default::default()
                })
                .await
                .unwrap();
            let (reader, mut writer) = TcpStream::connect(acp.address.as_ref().unwrap())
                .await
                .unwrap()
                .into_split();
            let mut socket = BufReader::new(reader);
            let initialized = acp_request(
                &mut socket,
                &mut writer,
                1,
                "initialize",
                json!({
                    "protocolVersion": 1,
                    "clientCapabilities": {},
                }),
            )
            .await;
            assert_eq!(
                initialized.0["agentInfo"]["version"],
                "sdk-transport-example"
            );
            let created = acp_request(
                &mut socket,
                &mut writer,
                2,
                "session/new",
                json!({
                    "cwd": ctx.work_dir(),
                    "mcpServers": [],
                }),
            )
            .await;
            let id = created.0["sessionId"].as_str().expect("ACP session id");
            let snapshot = owner
                .call(
                    "session.metadata.snapshot",
                    Some(json!({
                        "sessionId": id,
                    })),
                )
                .await
                .expect("the SDK controller must reach the ACP session on its own engine");
            assert_eq!(snapshot["sessionId"], id);
            assert_eq!(
                snapshot["workingDirectory"],
                ctx.work_dir().to_string_lossy().as_ref()
            );
            let policy = owner
                .call(
                    "session.managedSettings.get",
                    Some(json!({
                        "sessionId": id,
                    })),
                )
                .await
                .unwrap();
            assert_eq!(
                policy["bypassPermissionsDisabled"], true,
                "the ACP route must honor the host's required managed policy: {policy}"
            );
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest {
                    transport_id: acp.transport_id,
                })
                .await
                .expect("transport stop joins the ACP listener and its connections");
            let mut remainder = String::new();
            while socket.read_line(&mut remainder).await.unwrap() != 0 {
                remainder.clear();
            }

            // Successful stops join the listener task; reconnecting could reach an unrelated socket.
            owner
                .ping(None)
                .await
                .expect("the host outlives each stopped transport");

            let final_listener = owner
                .rpc()
                .host()
                .transports()
                .start(jsonrpc_listener())
                .await
                .unwrap();
            let final_address: SocketAddr = final_listener.address.unwrap().parse().unwrap();
            let final_peer = Client::start(peer(final_address, "sdk-host-transport-token"))
                .await
                .unwrap();
            owner
                .stop()
                .await
                .expect("host shutdown joins its remaining transports");
            assert!(final_peer.ping(None).await.is_err());
            final_peer.stop().await.unwrap();
        })
    })
    .await;
}

#[tokio::test]
async fn stdio_ends_after_peer_eof() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            for acp in [false, true] {
                let mut command = transport_fixture(ctx);
                command.arg("--stdio");
                if acp {
                    command.arg("--acp");
                }
                let mut child = command
                    .spawn()
                    .expect("start the runnable transport example");
                let mut input = child.stdin.take().unwrap();
                let output = child.stdout.take().unwrap();
                if acp {
                    let mut reader = BufReader::new(output);
                    let response = acp_request(
                        &mut reader,
                        &mut input,
                        1,
                        "initialize",
                        json!({
                            "protocolVersion": 1,
                        }),
                    )
                    .await;
                    assert_eq!(response.0["protocolVersion"], 1);
                    input.shutdown().await.unwrap();
                    drop(input);
                    let mut remainder = String::new();
                    while reader.read_line(&mut remainder).await.unwrap() != 0 {
                        remainder.clear();
                    }
                } else {
                    let peer = Client::from_streams(
                        copilot_sdk::ClientMode::CodingAgent,
                        output,
                        input,
                        ctx.work_dir().to_path_buf(),
                    )
                    .unwrap();
                    peer.verify_protocol_version().await.unwrap();
                    assert_eq!(
                        peer.ping(Some("stdio host")).await.unwrap().message,
                        "pong: stdio host"
                    );
                    peer.stop().await.unwrap();
                }
                let output = child.wait_with_output().await.unwrap();
                assert!(
                    output.status.success(),
                    "protocol acp={acp}: {}",
                    String::from_utf8_lossy(&output.stderr)
                );
            }
        })
    })
    .await;
}

#[tokio::test]
async fn protocol_peer_receives_runtime_shutdown_response() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let owner = Client::start(ctx.client_options_with_transport(
                RuntimeConnection::InProcess(InProcessRuntimeOptions::default()),
            ))
            .await
            .unwrap();
            let listener = owner
                .rpc()
                .host()
                .transports()
                .start(jsonrpc_listener())
                .await
                .unwrap();
            let (mut reader, writer) = TcpStream::connect(listener.address.unwrap())
                .await
                .unwrap()
                .into_split();
            // Observe server EOF before SDK teardown can close the peer.
            let (incoming, mut forwarded) = tokio::io::duplex(8192);
            let peer = Client::from_streams(
                copilot_sdk::ClientMode::CodingAgent,
                incoming,
                writer,
                ctx.work_dir().to_path_buf(),
            )
            .unwrap();
            let shutdown = async {
                peer.call(
                    "connect",
                    Some(json!({ "token": "sdk-host-transport-token" })),
                )
                .await
                .unwrap();
                peer.call("runtime.shutdown", Some(json!({})))
                    .await
                    .expect("shutdown must answer the peer before retiring its transport");
                owner.stop().await.unwrap();
            };
            let forwarding = async move {
                let result = tokio::io::copy(&mut reader, &mut forwarded).await;
                drop(forwarded);
                result
            };
            let (eof, ()) = tokio::join!(forwarding, shutdown);
            eof.expect("shutdown must close the original protocol connection");
            peer.stop().await.unwrap();
        })
    })
    .await;
}

#[tokio::test]
async fn stdio_stops_without_input_eof() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            for acp in [false, true] {
                let control = TcpListener::bind("127.0.0.1:0").await.unwrap();
                let mut command = transport_fixture(ctx);
                command
                    .arg("--control-address")
                    .arg(control.local_addr().unwrap().to_string());
                if acp {
                    command.arg("--acp");
                }
                #[cfg(unix)]
                let (mut input, output) = nonblocking_stdio(&mut command);
                let mut child = command.spawn().unwrap();
                #[cfg(not(unix))]
                let mut input = child.stdin.take().unwrap();
                #[cfg(not(unix))]
                let output = child.stdout.take().unwrap();
                let (mut control, _) = control.accept().await.unwrap();
                assert_eq!(control.read_u8().await.unwrap(), b'R');
                if acp {
                    let mut reader = BufReader::new(output);
                    acp_request(
                        &mut reader,
                        &mut input,
                        1,
                        "initialize",
                        json!({
                            "protocolVersion": 1,
                        }),
                    )
                    .await;
                    control.write_all(b"R").await.unwrap();
                    assert_eq!(control.read_u8().await.unwrap(), b'R');
                    acp_request(
                        &mut reader,
                        &mut input,
                        2,
                        "initialize",
                        json!({
                            "protocolVersion": 1,
                        }),
                    )
                    .await;
                    control.write_all(b"Q").await.unwrap();
                    assert!(child.wait().await.unwrap().success());
                    drop(input);
                } else {
                    let peer = Client::from_streams(
                        copilot_sdk::ClientMode::CodingAgent,
                        output,
                        input,
                        ctx.work_dir().to_path_buf(),
                    )
                    .unwrap();
                    peer.verify_protocol_version().await.unwrap();
                    assert_eq!(
                        peer.ping(Some("before stop")).await.unwrap().message,
                        "pong: before stop"
                    );
                    control.write_all(b"R").await.unwrap();
                    assert_eq!(control.read_u8().await.unwrap(), b'R');
                    assert_eq!(
                        peer.ping(Some("after stop")).await.unwrap().message,
                        "pong: after stop"
                    );
                    control.write_all(b"Q").await.unwrap();
                    assert!(child.wait().await.unwrap().success());
                    peer.stop().await.unwrap();
                }
            }
        })
    })
    .await;
}

#[tokio::test]
async fn stdio_acp_reports_a_broken_output_pipe() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let mut command = transport_fixture(ctx);
            command.args(["--stdio", "--acp"]);
            #[cfg(target_os = "linux")]
            let (mut input, output) = nonblocking_stdio(&mut command);
            #[cfg(target_os = "macos")]
            let (output_writer, output) = {
                use std::os::fd::OwnedFd;
                use std::os::unix::net::UnixStream;

                let (writer, reader) = UnixStream::pair().unwrap();
                writer.set_nonblocking(true).unwrap();
                reader.set_nonblocking(true).unwrap();
                command.stdout(std::process::Stdio::from(OwnedFd::from(
                    writer.try_clone().unwrap(),
                )));
                (writer, tokio::net::UnixStream::from_std(reader).unwrap())
            };
            let child = command.spawn().unwrap();
            #[cfg(not(target_os = "linux"))]
            let mut child = child;
            eprintln!("Broken-output ACP fixture spawned: {:?}", child.id());
            #[cfg(not(target_os = "linux"))]
            let mut input = child.stdin.take().unwrap();
            #[cfg(not(any(target_os = "linux", target_os = "macos")))]
            let output = child.stdout.take().unwrap();
            let mut output = BufReader::new(output);
            acp_request(&mut output, &mut input, 1, "initialize", json!({
                "protocolVersion": 1,
            })).await;
            eprintln!("Broken-output ACP initialization completed");
            // Keep a duplicate reader alive so failure cannot depend on closing the last reader.
            #[cfg(any(target_os = "linux", target_os = "macos"))]
            let inherited_output = std::os::fd::AsFd::as_fd(output.get_ref()).try_clone_to_owned().unwrap();
            // Linux propagates read-side shutdown to the peer's writer; Darwin does not.
            #[cfg(target_os = "linux")]
            output.into_inner().into_std().unwrap().shutdown(std::net::Shutdown::Read).unwrap();
            #[cfg(target_os = "macos")]
            output_writer.shutdown(std::net::Shutdown::Write).unwrap();
            #[cfg(not(target_os = "linux"))]
            drop(output);
            input.write_all(
                b"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}\n",
            ).await.unwrap();
            eprintln!("Broken-output ACP second request written; awaiting fixture exit with stdin open");
            let completed = child.wait_with_output().await.unwrap();
            eprintln!("Broken-output ACP fixture exited: {}", completed.status);
            #[cfg(any(target_os = "linux", target_os = "macos"))]
            drop(inherited_output);
            drop(input);
            let error = String::from_utf8_lossy(&completed.stderr);
            assert!(!completed.status.success(), "ACP silently accepted a broken output pipe");
            assert!(error.contains("host.transports.wait"), "wrong failure: {error}");
        })
    }).await;
}

#[tokio::test]
async fn stdio_rejects_reuse_after_consuming_an_incomplete_input_frame() {
    reject_incomplete_input_reuse(false).await;
}

#[tokio::test]
async fn acp_stdio_rejects_reuse_after_consuming_an_incomplete_input_frame() {
    reject_incomplete_input_reuse(true).await;
}

async fn reject_incomplete_input_reuse(acp: bool) {
    with_dedicated_e2e_context_no_snapshot(move |ctx| {
        Box::pin(async move {
            let control = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let mut command = transport_fixture(ctx);
            if acp {
                command.arg("--acp");
            }
            let mut child = command.arg("--control-address")
                .arg(control.local_addr().unwrap().to_string())
                .arg("--incomplete-input")
                .spawn()
                .unwrap();
            let mut input = child.stdin.take().unwrap();
            let mut output = BufReader::new(child.stdout.take().unwrap());
            let (mut control, _) = control.accept().await.unwrap();
            assert_eq!(control.read_u8().await.unwrap(), b'R');
            let ping = json!({
                "jsonrpc": "2.0", "id": 1, "method": "ping",
                "params": { "message": "input-boundary" },
            })
            .to_string();
            let bytes = if acp {
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}\n{\"jsonrpc\":".to_owned()
            } else {
                format!(
                "Content-Length: {}\r\n\r\n{ping}Content-Length: 100\r\n\r\n{{\"jsonrpc\":",
                ping.len(),
                )
            };
            input.write_all(bytes.as_bytes()).await.unwrap();
            if acp {
                let mut line = String::new();
                assert_ne!(output.read_line(&mut line).await.unwrap(), 0, "missing ACP initialize response");
                assert_eq!(serde_json::from_str::<Value>(&line).unwrap()["id"], 1);
            } else {
            let mut header = String::new();
            loop {
                let mut line = String::new();
                assert_ne!(
                    output.read_line(&mut line).await.unwrap(),
                    0,
                    "missing ping response"
                );
                header.push_str(&line);
                if line == "\r\n" {
                    break;
                }
            }
            let size: usize = header
                .lines()
                .find_map(|line| line.strip_prefix("Content-Length: "))
                .unwrap()
                .parse()
                .unwrap();
            let mut body = vec![0; size];
            output.read_exact(&mut body).await.unwrap();
            assert_eq!(serde_json::from_slice::<Value>(&body).unwrap()["id"], 1);
            }
            control.write_all(b"R").await.unwrap();
            let mut control = BufReader::new(control);
            let mut report = String::new();
            assert_ne!(
                control.read_line(&mut report).await.unwrap(),
                0,
                "missing stop report"
            );
            let report: Value = serde_json::from_str(&report).unwrap();
            control.get_mut().write_all(b"Q").await.unwrap();
            let completed = child.wait_with_output().await.unwrap();
            assert!(
                completed.status.success(),
                "fixture shutdown: {}",
                String::from_utf8_lossy(&completed.stderr)
            );
            assert!(
                report["stopError"].is_string(),
                "partial input reported clean shutdown: {report}"
            );
            assert!(
                report["restartError"].is_string(),
                "the consumed partial frame was discarded: {report}"
            );
        })
    })
    .await;
}

#[tokio::test]
async fn stdio_rejects_reuse_after_an_incomplete_output_frame() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            use std::sync::Arc;
            use std::sync::atomic::{AtomicBool, Ordering};
            use tokio::sync::oneshot;

            let control = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let mut child = transport_fixture(ctx)
                .arg("--control-address")
                .arg(control.local_addr().unwrap().to_string())
                .arg("--blocked-output")
                .spawn().unwrap();
            let input = child.stdin.take().unwrap();
            let mut output = child.stdout.take().unwrap();
            let (mut control, _) = control.accept().await.unwrap();
            assert_eq!(control.read_u8().await.unwrap(), b'R');
            let pause = Arc::new(AtomicBool::new(false));
            let pump_pause = Arc::clone(&pause);
            let (paused, reached) = oneshot::channel();
            let (release, resumed) = oneshot::channel();
            let (reader, mut pump) = tokio::io::duplex(8192);
            let mut tasks = tokio::task::JoinSet::new();
            tasks.spawn(async move {
                let mut pause_gate = Some((paused, resumed));
                let mut buffer = [0u8; 8192];
                loop {
                    let size = output.read(&mut buffer).await?;
                    if size == 0 { return Ok::<(), std::io::Error>(()); }
                    pump.write_all(&buffer[..size]).await?;
                    if pump_pause.load(Ordering::Acquire)
                        && let Some((paused, resumed)) = pause_gate.take()
                    {
                        paused.send(()).unwrap();
                        resumed.await.unwrap();
                    }
                }
            });
            let peer = Client::from_streams(
                copilot_sdk::ClientMode::CodingAgent,
                reader,
                input,
                ctx.work_dir().to_path_buf(),
            )
            .unwrap();
            peer.verify_protocol_version().await.unwrap();
            pause.store(true, Ordering::Release);
            let message = "x".repeat(3 * 1024 * 1024);
            let response = peer.ping(Some(&message));
            tokio::pin!(response);
            tokio::select! {
                ready = reached => ready.expect("the first response bytes must reach the paused reader"),
                result = &mut response => panic!("the oversized response finished before its reader paused: {result:?}"),
            }
            control.write_all(b"R").await.unwrap();
            let mut control = BufReader::new(control);
            let mut report = String::new();
            assert_ne!(control.read_line(&mut report).await.unwrap(), 0, "missing stop report");
            let report: Value = serde_json::from_str(&report).unwrap();
            release.send(()).unwrap();
            control.get_mut().write_all(b"Q").await.unwrap();
            let completed = child.wait_with_output().await.unwrap();
            while let Some(task) = tasks.join_next().await {
                task.unwrap().unwrap();
            }
            peer.stop().await.unwrap();
            assert!(completed.status.success(), "fixture shutdown: {}", String::from_utf8_lossy(&completed.stderr));
            assert!(report["stopError"].is_string(), "unfinished output reported success: {report}");
            assert!(report["restartError"].is_string(), "the incomplete stream was reusable: {report}");
        })
    }).await;
}

#[tokio::test]
async fn transport_stop_joins_native_orphan_cleanup() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            use copilot_sdk::SessionLifecycleMode;
            use std::future::{Future, poll_fn};
            use std::task::Poll;

            initialize_hook_repository(ctx);
            ctx.set_default_copilot_user();
            let gate = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let hooks = ctx.work_dir().join(".github/hooks");
            std::fs::create_dir_all(&hooks).unwrap();
            std::fs::write(
                ctx.work_dir().join("orphan-end.cjs"),
                format!(
                    "const socket = require('node:net').connect({}, '127.0.0.1');\n\
                     socket.on('connect', () => socket.write('R'));\n\
                     socket.once('data', () => socket.end());\n\
                     socket.on('error', error => {{ console.error(error); process.exitCode = 1; }});\n",
                    gate.local_addr().unwrap().port(),
                ),
            )
            .unwrap();
            std::fs::write(
                hooks.join("orphan-end.json"),
                json!({
                    "version": 1,
                    "hooks": {
                        "sessionEnd": [{
                            "type": "command",
                            "bash": "node orphan-end.cjs",
                            "powershell": "node orphan-end.cjs",
                        }],
                    },
                })
                .to_string(),
            )
            .unwrap();
            std::fs::create_dir_all(ctx.home_dir()).unwrap();
            std::fs::write(
                ctx.home_dir().join("config.json"),
                json!({ "trustedFolders": [ctx.work_dir()] }).to_string(),
            )
            .unwrap();
            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(InProcessRuntimeOptions::default()))
                    .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            )
            .await
            .unwrap();
            let transport = owner.rpc().host().transports().start(jsonrpc_listener()).await.unwrap();
            let address = transport.address.unwrap().parse().unwrap();
            let peer = Client::start(peer(address, "sdk-host-transport-token")).await.unwrap();
            // Use an explicit SDK hook because this session never takes a turn.
            let session = peer.create_session(
                ctx.approve_all_session_config()
                    .with_model("claude-sonnet-4.5")
                    .with_config_directory(ctx.home_dir())
                    .with_enable_config_discovery(true)
                    .with_hooks(|group| group.with_enable_repository_hooks(true))
                    .with_hooks(|group| group.with_directories([hooks]))
                    .with_session_lifecycle_mode(SessionLifecycleMode::Interactive),
            ).await.unwrap();
            let transports = owner.rpc().host().transports();
            let stop = transports.stop(HostTransportRequest { transport_id: transport.transport_id.clone() });
            tokio::pin!(stop);
            let mut premature = None;
            let (mut hook, _) = tokio::time::timeout(default_event_timeout(), async {
                tokio::select! {
                    result = &mut stop => {
                        premature = Some(result);
                        gate.accept().await.unwrap()
                    }
                    accepted = gate.accept() => accepted.unwrap(),
                }
            }).await.unwrap_or_else(|error| {
                panic!("the native sessionEnd hook did not start: {error}; transport stop: {premature:?}");
            });
            assert_eq!(hook.read_u8().await.unwrap(), b'R');
            if premature.is_none() {
                premature = poll_fn(|cx| match stop.as_mut().poll(cx) {
                    Poll::Ready(result) => Poll::Ready(Some(result)),
                    Poll::Pending => Poll::Ready(None),
                }).await;
            }
            let repeated_stop = tokio::time::timeout(
                default_event_timeout(),
                transports.stop(HostTransportRequest { transport_id: transport.transport_id }),
            ).await;
            hook.write_all(b"F").await.unwrap();
            let mut remaining = Vec::new();
            hook.read_to_end(&mut remaining).await.unwrap();
            let ended_before_cleanup = premature.is_some();
            match premature {
                Some(result) => result.unwrap(),
                None => stop.await.unwrap(),
            };
            drop(session);
            peer.stop().await.unwrap();
            owner.stop().await.unwrap();
            assert!(!ended_before_cleanup, "transport stop completed while the native sessionEnd hook still owned the workspace");
            let error = repeated_stop.expect("a nested stop waited for the active drain").unwrap_err();
            assert!(error.to_string().contains("stop is already in progress"), "{error}");
        })
    }).await;
}

#[tokio::test]
async fn acp_close_reports_a_native_flush_failure() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            scripted_acp(
                ctx,
                json!([{
                    "requestIncludes": "ACP_CLOSE_PERSISTED_TURN",
                    "content": "ACP_CLOSE_PERSISTED_REPLY",
                }]),
            );
            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::default(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            )
            .await
            .unwrap();
            let (transport_id, mut reader, mut writer) =
                connect_acp(&owner, ctx, acp_defaults()).await;
            let (created, _) = acp_request(
                &mut reader,
                &mut writer,
                2,
                "session/new",
                json!({ "cwd": ctx.work_dir(), "mcpServers": [] }),
            )
            .await;
            let id = created["sessionId"].as_str().unwrap();
            acp_request(
                &mut reader,
                &mut writer,
                3,
                "session/prompt",
                json!({
                    "sessionId": id,
                    "prompt": [{ "type": "text", "text": "ACP_CLOSE_PERSISTED_TURN" }],
                }),
            )
            .await;
            owner
                .rpc()
                .sessions()
                .save(SessionsSaveRequest {
                    session_id: id.into(),
                })
                .await
                .unwrap();
            let journal = ctx
                .home_dir()
                .join("session-state")
                .join(id)
                .join("events.jsonl");
            let saved = journal.with_extension("saved");
            std::fs::rename(&journal, &saved).unwrap();
            std::fs::create_dir(&journal).unwrap();
            let (closed, _) = acp_response(
                &mut reader,
                &mut writer,
                4,
                "session/close",
                json!({ "sessionId": id }),
            )
            .await;
            std::fs::remove_dir(&journal).unwrap();
            std::fs::rename(&saved, &journal).unwrap();
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            owner.stop().await.unwrap();
            assert!(
                closed["error"].is_object(),
                "ACP reported success after native journal failure: {closed}"
            );
        })
    })
    .await;
}

#[tokio::test]
async fn acp_logout_clears_all_protocol_peers_on_the_host() {
    use std::sync::Arc;

    use copilot_sdk::{
        GitHubToken, GitHubTokenProvider, GitHubTokenProviderArgs, GitHubTokenProviderResult,
    };

    struct HeldToken {
        entered: tokio::sync::Notify,
        release: tokio::sync::watch::Sender<bool>,
        token: &'static str,
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
                self.token,
                8 * 60 * 60,
            )))
        }
    }

    const CHILD: &str = "COPILOT_SDK_TEST_ACP_LOGOUT_CHILD";
    if std::env::var(CHILD).as_deref() != Ok("1") {
        let home = tempfile::tempdir().unwrap();
        let mut command = tokio::process::Command::new(std::env::current_exe().unwrap());
        command
            .current_dir(std::env::current_dir().unwrap())
            .args([
                "--exact",
                "e2e_cases::host_transports::acp_logout_clears_all_protocol_peers_on_the_host",
                "--nocapture",
            ])
            .env(CHILD, "1")
            .env("COPILOT_DISABLE_KEYTAR", "1")
            .env("HOME", home.path())
            .env("USERPROFILE", home.path())
            .env("GH_CONFIG_DIR", home.path())
            .kill_on_drop(true);
        for key in [
            "COPILOT_GITHUB_TOKEN",
            "GH_TOKEN",
            "GITHUB_TOKEN",
            "GH_ENTERPRISE_TOKEN",
            "GITHUB_ENTERPRISE_TOKEN",
            "CAPI_HMAC_KEY",
            "COPILOT_HMAC_KEY",
        ] {
            command.env_remove(key);
        }
        #[cfg(windows)]
        {
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        let output = command.output().await.unwrap();
        assert!(
            output.status.success() && String::from_utf8_lossy(&output.stdout).contains("1 passed"),
            "isolated logout case:\n{}\n{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        return;
    }
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let token = "gho_sdk_transport_logout_fixture";
            ctx.set_copilot_user_by_token(token);
            let independent_token = "gho_sdk_independent_logout_fixture";
            ctx.set_copilot_user_by_token_with_login(independent_token, "independent-user");
            std::fs::create_dir_all(ctx.home_dir()).unwrap();
            std::fs::write(
                ctx.home_dir().join("settings.json"),
                r#"{"storeTokenPlaintext":true}"#,
            )
            .unwrap();
            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::default(),
                ))
                .with_github(|group| group.with_use_logged_in_user(true)),
            )
            .await
            .unwrap();
            let logged_in = owner
                .call(
                    "account.login",
                    Some(json!({
                        "host": "https://github.com", "token": token,
                    })),
                )
                .await
                .unwrap();
            assert_eq!(logged_in["storedInVault"], true, "{logged_in}");
            let (transport_id, mut first_reader, mut first_writer) =
                connect_acp(&owner, ctx, acp_defaults()).await;
            let (reader, mut second_writer) = TcpStream::connect(first_writer.peer_addr().unwrap())
                .await
                .unwrap()
                .into_split();
            let mut second_reader = BufReader::new(reader);
            acp_request(
                &mut second_reader,
                &mut second_writer,
                1,
                "initialize",
                json!({
                    "protocolVersion": 1,
                }),
            )
            .await;
            let (first, _) = acp_request(
                &mut first_reader,
                &mut first_writer,
                2,
                "session/new",
                json!({ "cwd": ctx.work_dir(), "mcpServers": [] }),
            )
            .await;
            let (second, _) = acp_request(
                &mut second_reader,
                &mut second_writer,
                2,
                "session/new",
                json!({ "cwd": ctx.work_dir(), "mcpServers": [] }),
            )
            .await;
            let jsonrpc = owner
                .rpc()
                .host()
                .transports()
                .start(jsonrpc_listener())
                .await
                .unwrap();
            let rpc_peer = Client::start(peer(
                jsonrpc.address.unwrap().parse().unwrap(),
                "sdk-host-transport-token",
            ))
            .await
            .unwrap();
            let shared_config = copilot_sdk::CreateSessionOptions::default()
                .with_model("claude-sonnet-4.5")
                .with_working_directory(ctx.work_dir())
                .with_enable_config_discovery(false)
                .with_github(|group| group.with_fetch_managed_settings(false))
                .with_permissions(|group| {
                    group.with_on_request(std::sync::Arc::new(
                        copilot_sdk::handler::ApproveAllHandler,
                    ))
                });
            let rpc_session = rpc_peer
                .create_session(shared_config.clone())
                .await
                .unwrap();
            let owner_session = owner.create_session(shared_config.clone()).await.unwrap();
            let provider = Arc::new(HeldToken {
                entered: tokio::sync::Notify::new(),
                release: tokio::sync::watch::channel(false).0,
                token: independent_token,
            });
            let creating = rpc_peer.create_session(
                shared_config.with_github(|group| group.with_token_provider(provider.clone())),
            );
            tokio::pin!(creating);
            tokio::select! {
                () = provider.entered.notified() => {}
                result = &mut creating => panic!(
                    "creation finished before its SDK callback: {:?}",
                    result.as_ref().map(|session| session.id()),
                ),
            }
            let busy_logout = tokio::time::timeout(
                default_event_timeout(),
                acp_response(
                    &mut second_reader,
                    &mut second_writer,
                    3,
                    "auth/logout",
                    json!({}),
                ),
            )
            .await;
            provider.release.send_replace(true);
            let created = creating.await;
            let (busy_logout, _) = busy_logout.expect("logout waited for an admitted SDK callback");
            assert!(
                busy_logout["error"]["message"]
                    .as_str()
                    .is_some_and(|message| message.contains("Host authentication is busy")),
                "logout did not reject concurrent SDK admission: {busy_logout}",
            );
            let independent = created.unwrap();
            let independent_before = owner
                .call(
                    "session.gitHubAuth.getCurrentAuthInfo",
                    Some(json!({
                        "sessionId": independent.id(),
                    })),
                )
                .await
                .unwrap();
            assert!(
                !independent_before.is_null(),
                "the independent credential was not installed"
            );
            let mut shared_ids = vec![
                first["sessionId"].clone(),
                second["sessionId"].clone(),
                json!(rpc_session.id()),
                json!(owner_session.id()),
            ];
            for id in &shared_ids {
                let before = owner
                    .call(
                        "session.gitHubAuth.getCurrentAuthInfo",
                        Some(json!({
                            "sessionId": id,
                        })),
                    )
                    .await
                    .unwrap();
                assert_eq!(before["login"], "rust-e2e-user", "{before}");
            }
            let current = owner
                .call("account.getCurrentAuth", Some(json!({})))
                .await
                .unwrap();
            let raw = owner
                .call(
                    "sessions.open",
                    Some(json!({
                        "kind": "create",
                        "options": {
                            "workingDirectory": ctx.work_dir(),
                            "model": "claude-sonnet-4.5",
                            "enableConfigDiscovery": false,
                            "github": {
                                "fetchManagedSettings": false,
                                "authInfo": current["authInfo"],
                            },
                        },
                    })),
                )
                .await
                .unwrap();
            assert_eq!(raw["status"], "created", "{raw}");
            acp_request(
                &mut second_reader,
                &mut second_writer,
                4,
                "auth/logout",
                json!({}),
            )
            .await;
            let attached = owner
                .call(
                    "sessions.open",
                    Some(json!({ "kind": "attach", "sessionId": raw["sessionId"] })),
                )
                .await
                .unwrap();
            assert_eq!(attached["status"], "resumed", "{attached}");
            shared_ids.push(raw["sessionId"].clone());
            let mut remaining = Vec::new();
            for id in &shared_ids {
                remaining.push(
                    owner
                        .call(
                            "session.gitHubAuth.getCurrentAuthInfo",
                            Some(json!({
                                "sessionId": id,
                            })),
                        )
                        .await
                        .unwrap(),
                );
            }
            let independent_after = owner
                .call(
                    "session.gitHubAuth.getCurrentAuthInfo",
                    Some(json!({
                        "sessionId": independent.id(),
                    })),
                )
                .await
                .unwrap();
            rpc_session.disconnect().await.unwrap();
            owner_session.disconnect().await.unwrap();
            independent.disconnect().await.unwrap();
            rpc_peer.stop().await.unwrap();
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest {
                    transport_id: jsonrpc.transport_id,
                })
                .await
                .unwrap();
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            owner.stop().await.unwrap();
            assert_eq!(
                remaining,
                vec![Value::Null; shared_ids.len()],
                "logout left stale peer authentication"
            );
            assert_eq!(
                independent_after, independent_before,
                "logout cleared an independent credential"
            );
        })
    })
    .await;
}

#[tokio::test]
async fn acp_inherits_host_lifecycle_mode_unless_the_transport_overrides_it() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            use copilot_sdk::SessionLifecycleMode;

            initialize_hook_repository(ctx);
            scripted_acp(ctx, json!([
                { "requestIncludes": "ACP_HOST_HOOK_MODE", "content": "ACP_HOST_HOOK_MODE_DONE" },
                { "requestIncludes": "ACP_HOST_COLD_HOOK_MODE", "content": "ACP_HOST_COLD_HOOK_MODE_DONE" },
                { "requestIncludes": "ACP_TRANSPORT_HOOK_MODE", "content": "ACP_TRANSPORT_HOOK_MODE_DONE" },
            ]));
            let marker = ctx.work_dir().join("lifecycle-hook-ended");
            let hooks = ctx.work_dir().join(".github/hooks");
            std::fs::create_dir_all(&hooks).unwrap();
            std::fs::write(ctx.work_dir().join("lifecycle-end.cjs"),
                format!("require('node:fs').appendFileSync({}, 'end\\n');", json!(marker))).unwrap();
            std::fs::write(hooks.join("lifecycle.json"), json!({
                "version": 1,
                "hooks": {
                    "sessionEnd": [{
                        "type": "command",
                        "bash": "node lifecycle-end.cjs",
                        "powershell": "node lifecycle-end.cjs",
                    }],
                },
            }).to_string()).unwrap();
            std::fs::create_dir_all(ctx.home_dir()).unwrap();
            std::fs::write(ctx.home_dir().join("config.json"), json!({
                "trustedFolders": [ctx.work_dir()],
            }).to_string()).unwrap();
            let host_log = ctx.work_dir().join("acp-lifecycle-host.log");
            let owner = Client::start_with_host_configuration(ctx.client_options_with_transport(RuntimeConnection::InProcess(InProcessRuntimeOptions {
                file_logging_path: Some(host_log.clone()),
                ..Default::default()
            }))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN))
                .with_observability(|group| group.with_log_level(copilot_sdk::LogLevel::Debug)),
                RuntimeHostConfiguration {
                    session_lifecycle_mode: Some(SessionLifecycleMode::Interactive),
                    ..Default::default()
                }
                ).await.unwrap();
            let mut observed = Vec::new();
            let mut first_session_id = None;
            for (override_mode, prompt) in [
                (false, "ACP_HOST_HOOK_MODE"),
                (true, "ACP_TRANSPORT_HOOK_MODE"),
            ] {
                let mut defaults = acp_defaults();
                defaults["enableConfigDiscovery"] = json!(true);
                defaults["hooks"] = json!({ "enableRepositoryHooks": true });
                if override_mode {
                    defaults["sessionLifecycleMode"] = json!("per-turn");
                }
                let (transport_id, mut reader, mut writer) = connect_acp(&owner, ctx, defaults).await;
                let (created, _) = acp_request(&mut reader, &mut writer, 2, "session/new",
                    json!({ "cwd": ctx.work_dir(), "mcpServers": [] })).await;
                let prompts = if override_mode {
                    vec![prompt]
                } else {
                    vec![prompt, "ACP_HOST_COLD_HOOK_MODE"]
                };
                for (index, prompt) in prompts.into_iter().enumerate() {
                    if index > 0 {
                        acp_request(&mut reader, &mut writer, 2, "session/load", json!({
                            "sessionId": created["sessionId"], "cwd": ctx.work_dir(), "mcpServers": [],
                        })).await;
                    }
                    acp_request(&mut reader, &mut writer, 3, "session/prompt", json!({
                        "sessionId": created["sessionId"],
                        "prompt": [{ "type": "text", "text": prompt }],
                    })).await;
                    let ran_before_close = marker.is_file();
                    acp_request(&mut reader, &mut writer, 4, "session/close",
                        json!({ "sessionId": created["sessionId"] })).await;
                    let ran_after_close = marker.is_file();
                    observed.push((ran_before_close, ran_after_close));
                    if !override_mode && index == 0 {
                        let session_id = created["sessionId"].as_str().unwrap();
                        first_session_id = Some(session_id.to_owned());
                        let journal = ctx.home_dir().join("session-state").join(session_id).join("events.jsonl");
                        let evidence = std::fs::read_to_string(&journal)
                            .map_err(|error| error.to_string())
                            .and_then(|contents| {
                                contents.lines().map(serde_json::from_str::<Value>)
                                    .collect::<Result<Vec<_>, _>>()
                                    .map_err(|error| error.to_string())
                            })
                            .map(|events| events.into_iter().filter(|event| {
                                (matches!(event["type"].as_str(), Some("hook.start" | "hook.end"))
                                    && event["data"]["hookType"] == "sessionEnd")
                                    || matches!(event["type"].as_str(), Some("session.error" | "session.warning" | "session.shutdown"))
                            }).collect::<Vec<_>>());
                        eprintln!("ACP_FIRST_CLOSE_EVIDENCE {}", json!({
                            "sessionId": session_id,
                            "phase": "interactive-first-close",
                            "journal": journal,
                            "markerBefore": ran_before_close,
                            "markerAfter": ran_after_close,
                            "hookEvidence": evidence,
                        }));
                    }
                    if marker.is_file() {
                        assert_eq!(std::fs::read_to_string(&marker).unwrap(), "end\n");
                        std::fs::remove_file(&marker).unwrap();
                    }
                }
                owner.rpc().host().transports().stop(HostTransportRequest { transport_id }).await.unwrap();
            }
            owner.stop().await.unwrap();
            let expected = [(false, true), (false, true), (true, true)];
            if observed != expected {
                let session_log = std::fs::read_to_string(&host_log).map(|log| {
                    log.lines().filter(|line| {
                        first_session_id.as_deref().is_some_and(|id| line.contains(id))
                    }).map(str::to_owned).collect::<Vec<_>>()
                });
                eprintln!("ACP_FIRST_CLOSE_SESSION_LOG {:?}", session_log);
            }
            assert_eq!(observed, expected,
                "ACP ignored host defaults or the explicit transport override");
        })
    }).await;
}

#[tokio::test]
async fn acp_honors_user_bypass_restrictions_from_each_host_config_form() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            let owner = Client::start(ctx.client_options_with_transport(RuntimeConnection::InProcess(InProcessRuntimeOptions::default()))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN))).await.unwrap();
            let mut observed = Vec::new();
            for form in ["configDir", "configDirectory", "runtimeSettings"] {
                let directory = ctx.home_dir().join(form);
                std::fs::create_dir_all(&directory).unwrap();
                std::fs::write(directory.join("settings.json"), json!({
                    "permissions": { "disableBypassPermissionsMode": "disable" },
                }).to_string()).unwrap();
                let mut defaults = acp_defaults();
                if form == "runtimeSettings" {
                    defaults[form] = json!({ "configDir": directory });
                } else {
                    defaults[form] = json!(directory);
                }
                let (transport_id, mut reader, mut writer) =
                    connect_acp(&owner, ctx, defaults).await;
                let (created, _) = acp_request(&mut reader, &mut writer, 2, "session/new",
                    json!({ "cwd": ctx.work_dir(), "mcpServers": [] })).await;
                let (response, _) = acp_response(&mut reader, &mut writer, 3,
                    "session/set_config_option",
                    json!({ "sessionId": created["sessionId"], "configId": "allow_all", "value": "on" }),
                ).await;
                observed.push((form, response));
                acp_request(&mut reader, &mut writer, 4, "session/close",
                    json!({ "sessionId": created["sessionId"] })).await;
                owner.rpc().host().transports().stop(HostTransportRequest { transport_id })
                    .await.unwrap();
            }
            owner.stop().await.unwrap();
            for (form, response) in observed {
                assert!(response["error"].is_object(),
                    "{form} ignored the user's bypass restriction: {response}");
            }
        })
    }).await;
}

#[tokio::test]
async fn acp_default_plugin_inventory_keeps_ambient_hooks() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            // An owned Git root excludes unrelated enclosing-repository plugin activation.
            initialize_hook_repository(ctx);
            scripted_acp(ctx, json!([
                { "requestIncludes": "SDK_PLUGIN_INVENTORY_CONTROL", "content": "SDK_PLUGIN_CONTROL_DONE" },
                { "requestIncludes": "SDK_PLAIN_EMPTY_PLUGIN_INVENTORY", "content": "SDK_PLAIN_EMPTY_PLUGIN_DONE" },
                { "requestIncludes": "SDK_FORGED_PLUGIN_INVENTORY", "content": "SDK_FORGED_PLUGIN_DONE" },
                { "requestIncludes": "ACP_PLUGIN_INVENTORY", "content": "ACP_PLUGIN_DONE" },
            ]));
            // The install parser rejects Windows verbatim paths returned by home_dir().
            let plugin = ctx.work_dir().join("ambient-hook-plugin");
            let marker = ctx.work_dir().join("ambient-hook-ran");
            std::fs::create_dir_all(&plugin).unwrap();
            std::fs::write(plugin.join("plugin.json"), r#"{"name":"ambient-hook","version":"1.0.0"}"#).unwrap();
            std::fs::write(plugin.join("record-start.cjs"),
                format!("const fs = require('node:fs'); fs.writeFileSync({}, fs.readFileSync(0, 'utf8'));", json!(marker))).unwrap();
            let script = "node \"${PLUGIN_ROOT}/record-start.cjs\"";
            std::fs::write(plugin.join("hooks.json"), json!({
                "hooks": { "sessionStart": [{ "type": "command", "bash": script, "powershell": script }] },
            }).to_string()).unwrap();
            std::fs::write(ctx.home_dir().join("config.json"), json!({
                "trustedFolders": [ctx.work_dir()],
            }).to_string()).unwrap();
            // A direct install supplies ambient inventory without requesting marketplace activation.
            let owner = Client::start(ctx.client_options_with_transport(RuntimeConnection::InProcess(InProcessRuntimeOptions::default()))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN))).await.unwrap();
            let installed = owner.call("plugins.install", Some(json!({
                "source": plugin,
            }))).await.unwrap();
            assert_eq!(installed["plugin"]["name"], "ambient-hook", "{installed}");
            let discovered = owner.call("hooks.discover", Some(json!({
                "projectPaths": [ctx.work_dir()],
            }))).await.unwrap();
            let control = owner.create_session(ctx.approve_all_session_config()
                .with_model("claude-sonnet-4.5")
                .with_config_directory(ctx.home_dir())
                .with_enable_config_discovery(true)
                .with_hooks(|group| group.with_enable_repository_hooks(true))
                .with_github(|group| group.with_fetch_managed_settings(false))
                .with_extensions(|group| group.with_enabled(false))).await.unwrap();
            control.send_and_wait("SDK_PLUGIN_INVENTORY_CONTROL").await.unwrap();
            let events = control.get_events().await.unwrap();
            control.disconnect().await.unwrap();
            assert!(marker.is_file(), "the SDK control did not run the fixture plugin: {discovered}; events: {events:?}");
            std::fs::remove_file(&marker).unwrap();
            let plain = owner.call("session.create", Some(json!({
                "model": "claude-sonnet-4.5", "workingDirectory": ctx.work_dir(),
                "configDirectory": ctx.home_dir(), "enableConfigDiscovery": true,
                "hooks": { "enableRepositoryHooks": true },
                "github": { "fetchManagedSettings": false },
                "extensions": { "enabled": false },
                "plugins": { "installed": [] },
            }))).await.unwrap();
            let plain = owner.resume_session(ResumeSessionOptions::new(plain["sessionId"].as_str().unwrap().into())
                .with_permissions(|group| group.with_on_request(std::sync::Arc::new(copilot_sdk::handler::ApproveAllHandler))))
                .await.unwrap();
            plain.send_and_wait("SDK_PLAIN_EMPTY_PLUGIN_INVENTORY").await.unwrap();
            let plain_inventory = owner.call("session.plugins.list", Some(json!({
                "sessionId": plain.id(),
            }))).await.unwrap();
            let plain_events = plain.get_events().await.unwrap();
            plain.disconnect().await.unwrap();
            let plain_marker = std::fs::read_to_string(&marker);
            if marker.exists() {
                std::fs::remove_file(&marker).unwrap();
            }
            let forged = owner.call("session.create", Some(json!({
                "model": "claude-sonnet-4.5", "workingDirectory": ctx.work_dir(),
                "configDirectory": ctx.home_dir(), "enableConfigDiscovery": true,
                "hooks": { "enableRepositoryHooks": true },
                "github": { "fetchManagedSettings": false },
                "extensions": { "enabled": false },
                "plugins": { "installed": [] }, "__serverDefaultInstalledPlugins": true,
                "__nativeCliExpAudience": { "isExperimental": "invalid" },
            }))).await.unwrap();
            let created_inventory = owner.call("session.plugins.list", Some(json!({
                "sessionId": forged["sessionId"],
            }))).await.unwrap();
            let forged = owner.resume_session(ResumeSessionOptions::new(forged["sessionId"].as_str().unwrap().into())
                .with_permissions(|group| group.with_on_request(std::sync::Arc::new(copilot_sdk::handler::ApproveAllHandler))))
                .await.unwrap();
            let resumed_inventory = owner.call("session.plugins.list", Some(json!({
                "sessionId": forged.id(),
            }))).await.unwrap();
            forged.send_and_wait("SDK_FORGED_PLUGIN_INVENTORY").await.unwrap();
            let turn_inventory = owner.call("session.plugins.list", Some(json!({
                "sessionId": forged.id(),
            }))).await.unwrap();
            let forged_events = forged.get_events().await.unwrap();
            forged.disconnect().await.unwrap();
            let unexpected_marker = std::fs::read_to_string(&marker);
            assert!(!marker.exists(),
                "an ordinary SDK client forged the default-only plugin marker; control: {}; plain: {}; plain hook input: {plain_marker:?}; plain inventory: {plain_inventory}; plain events: {plain_events:?}; forged: {}; hook input: {unexpected_marker:?}; inventory after create: {created_inventory}; after resume: {resumed_inventory}; after turn: {turn_inventory}; events: {forged_events:?}",
                control.id(), plain.id(), forged.id());
            let mut defaults = acp_defaults();
            defaults["enableConfigDiscovery"] = json!(true);
            defaults["hooks"] = json!({ "enableRepositoryHooks": true });
            defaults["plugins"] = json!({ "installed": [] });
            defaults["__serverDefaultInstalledPlugins"] = json!(true);
            let (transport_id, mut reader, mut writer) = connect_acp(&owner, ctx, defaults).await;
            let (created, _) = acp_request(&mut reader, &mut writer, 2, "session/new",
                json!({ "cwd": ctx.work_dir(), "mcpServers": [] })).await;
            acp_request(&mut reader, &mut writer, 3, "session/prompt", json!({
                "sessionId": created["sessionId"],
                "prompt": [{ "type": "text", "text": "ACP_PLUGIN_INVENTORY" }],
            })).await;
            owner.rpc().host().transports().stop(HostTransportRequest { transport_id }).await.unwrap();
            owner.stop().await.unwrap();
            assert!(marker.is_file(), "ACP treated the default-only inventory as complete and skipped the ambient hook");
        })
    }).await;
}

#[tokio::test]
async fn owned_acp_accepts_named_byok_defaults_without_a_github_account() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::default(),
                ))
                .with_github(|group| group.with_use_logged_in_user(false)),
            )
            .await
            .unwrap();
            let defaults = json!({
                "model": "local/selected",
                "enableConfigDiscovery": false,
                "github": { "capi": { "enabled": false }, "fetchManagedSettings": false },
                "extensions": { "enabled": false },
                "inference": {
                    "providers": [{
                        "name": "local", "type": "openai", "wireApi": "completions",
                        "baseUrl": ctx.proxy_url(), "apiKey": "synthetic-byok-key",
                    }],
                    "models": [{
                        "id": "selected", "provider": "local", "modelId": "claude-sonnet-4.5",
                    }],
                },
            });
            let (transport_id, mut reader, mut writer) =
                connect_acp(&owner, ctx, defaults.clone()).await;
            let (response, _) = acp_response(
                &mut reader,
                &mut writer,
                2,
                "session/new",
                json!({ "cwd": ctx.work_dir(), "mcpServers": [] }),
            )
            .await;
            let imported = owner
                .rpc()
                .sessions()
                .import(imported_conversation(ctx))
                .await;
            let closed = if let Ok(imported) = &imported
                && let Some(id) = &imported.session_id
            {
                Some(
                    owner
                        .call("sessions.close", Some(json!({ "sessionId": id })))
                        .await,
                )
            } else {
                None
            };
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            owner.stop().await.unwrap();
            assert!(response.get("error").is_none(), "{response}");
            assert!(response["result"]["sessionId"].is_string(), "{response}");
            let imported = imported.unwrap();
            assert!(imported.ok, "{imported:?}");
            let cold_session_id = imported.session_id.expect("imported session id");
            closed.expect("close imported source").unwrap();

            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::default(),
                ))
                .with_github(|group| group.with_use_logged_in_user(false)),
            )
            .await
            .unwrap();
            let (transport_id, mut reader, mut writer) = connect_acp(&owner, ctx, defaults).await;
            let (loaded, _) = acp_response(
                &mut reader,
                &mut writer,
                2,
                "session/load",
                json!({
                    "sessionId": cold_session_id,
                    "cwd": ctx.work_dir(), "mcpServers": [],
                }),
            )
            .await;
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            owner.stop().await.unwrap();
            assert!(loaded.get("error").is_none(), "{loaded}");
        })
    })
    .await;
}

#[tokio::test]
async fn acp_displays_text_without_a_streaming_override() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            scripted_acp(
                ctx,
                json!([{
                    "requestIncludes": "ACP_STREAMING_PROMPT",
                    "content": "ACP_STREAMING_REPLY",
                }]),
            );
            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::default(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            )
            .await
            .unwrap();
            let (transport_id, mut reader, mut writer) =
                connect_acp(&owner, ctx, acp_defaults()).await;
            let (created, _) = acp_request(
                &mut reader,
                &mut writer,
                2,
                "session/new",
                json!({
                    "cwd": ctx.work_dir(), "mcpServers": [],
                }),
            )
            .await;
            let (completed, updates) = acp_request(
                &mut reader,
                &mut writer,
                3,
                "session/prompt",
                json!({
                    "sessionId": created["sessionId"],
                    "prompt": [{ "type": "text", "text": "ACP_STREAMING_PROMPT" }],
                }),
            )
            .await;
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            owner.stop().await.unwrap();
            assert_eq!(completed["stopReason"], "end_turn");
            let text = updates
                .iter()
                .filter(|update| update["update"]["sessionUpdate"] == "agent_message_chunk")
                .filter_map(|update| {
                    update
                        .pointer("/update/content/text")
                        .and_then(Value::as_str)
                })
                .collect::<String>();
            let update_types = updates
                .iter()
                .map(|update| &update["update"]["sessionUpdate"])
                .collect::<Vec<_>>();
            assert_eq!(
                text, "ACP_STREAMING_REPLY",
                "ACP update types: {update_types:?}"
            );
        })
    })
    .await;
}

#[tokio::test]
async fn acp_permission_requests_reach_the_protocol_peer() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            let shell = if cfg!(windows) { "powershell" } else { "bash" };
            scripted_acp(ctx, json!([
                {
                    "requestIncludes": "ACP_PERMISSION_PROMPT",
                    "toolCalls": [{
                        "id": "acp_permission_check", "name": shell,
                        "arguments": {
                            "command": "node -e \"require('fs').writeFileSync('acp-permission-marker', 'unexpected')\"",
                            "description": "Write the permission fixture marker", "initial_wait": 30,
                        },
                    }],
                },
                { "requestIncludes": "ACP_PERMISSION_PROMPT", "content": "ACP_PERMISSION_DENIED" },
            ]));
            let owner = Client::start(ctx.client_options_with_transport(RuntimeConnection::InProcess(InProcessRuntimeOptions::default()))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN))).await.unwrap();
            let mut defaults = acp_defaults();
            defaults["experiments"] = json!({ "flags": { "AUTO_APPROVAL": false } });
            let (transport_id, mut reader, mut writer) = connect_acp(&owner, ctx, defaults).await;
            let (created, _) = acp_request(&mut reader, &mut writer, 2, "session/new", json!({
                "cwd": ctx.work_dir(), "mcpServers": [],
            })).await;
            let prompt = json!({
                "jsonrpc": "2.0", "id": 3, "method": "session/prompt",
                "params": {
                    "sessionId": created["sessionId"],
                    "prompt": [{ "type": "text", "text": "ACP_PERMISSION_PROMPT" }],
                },
            });
            writer.write_all(format!("{prompt}\n").as_bytes()).await.unwrap();
            let mut requests = 0;
            loop {
                let mut line = String::new();
                assert_ne!(reader.read_line(&mut line).await.unwrap(), 0, "ACP ended during permission handling");
                let message: Value = serde_json::from_str(&line).unwrap();
                if message["method"] == "session/request_permission" {
                    requests += 1;
                    let reject = message["params"]["options"].as_array().unwrap().iter()
                        .find(|option| option["kind"] == "reject_once").expect("single-use rejection");
                    let response = json!({
                        "jsonrpc": "2.0", "id": message["id"],
                        "result": { "outcome": { "outcome": "selected", "optionId": reject["optionId"] } },
                    });
                    writer.write_all(format!("{response}\n").as_bytes()).await.unwrap();
                } else if message["id"] == 3 {
                    assert!(message.get("error").is_none(), "{message}");
                    break;
                }
            }
            owner.rpc().host().transports().stop(HostTransportRequest { transport_id }).await.unwrap();
            owner.stop().await.unwrap();
            assert_eq!(requests, 1, "the native lifecycle bypassed the ACP permission peer");
            assert!(!ctx.work_dir().join("acp-permission-marker").exists());
        })
    }).await;
}

#[tokio::test]
async fn acp_preserves_the_missing_session_response() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::default(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            )
            .await
            .unwrap();
            let (transport_id, mut reader, mut writer) =
                connect_acp(&owner, ctx, acp_defaults()).await;
            let missing = uuid::Uuid::new_v4().to_string();
            let (response, _) = acp_response(
                &mut reader,
                &mut writer,
                2,
                "session/load",
                json!({
                    "sessionId": missing, "cwd": ctx.work_dir(), "mcpServers": [],
                }),
            )
            .await;
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            owner.stop().await.unwrap();
            assert_eq!(response["error"]["code"], -32002, "{response}");
        })
    })
    .await;
}

#[tokio::test]
async fn acp_and_sdk_release_only_their_own_session_attachments() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            let owner = Client::start(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(InProcessRuntimeOptions::default()))
                    .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
            )
                .await
                .unwrap();
            let mut defaults = acp_defaults();
            defaults["filesystem"] = json!({ "trustWorkingDirectory": "session" });
            defaults["instructions"] = json!({ "refreshCustomInstructions": false });
            let (transport_id, mut reader, mut writer) = connect_acp(&owner, ctx, defaults).await;
            let (created, _) = acp_request(&mut reader, &mut writer, 2, "session/new", json!({
                "cwd": ctx.work_dir(), "mcpServers": [],
            })).await;
            let acp_id = created["sessionId"].as_str().unwrap();
            let observer = owner.resume_session(
                ResumeSessionOptions::new(acp_id.into())
                    .with_permissions(|group| group.with_on_request(std::sync::Arc::new(copilot_sdk::handler::ApproveAllHandler)))
                    .with_enable_config_discovery(false),
            ).await.unwrap();
            observer.disconnect().await.unwrap();
            let acp_after_detach = owner.call("session.metadata.snapshot", Some(json!({
                "sessionId": acp_id,
            }))).await;

            let sdk_session = owner.create_session(
                ctx.approve_all_session_config()
                    .with_enable_config_discovery(false)
                    .with_github(|group| group.with_fetch_managed_settings(false))
                    .with_name("SDK attachment owner"),
            ).await.unwrap();
            owner.rpc().sessions().save(SessionsSaveRequest {
                session_id: sdk_session.id().clone(),
            }).await.unwrap();
            acp_request(&mut reader, &mut writer, 3, "session/load", json!({
                "sessionId": sdk_session.id(),
                "cwd": ctx.work_dir(),
                "mcpServers": [],
            })).await;
            owner.rpc().host().transports().stop(HostTransportRequest { transport_id }).await.unwrap();
            let sdk_after_detach = sdk_session.get_events().await;
            let stopped = owner.stop().await;
            assert!(acp_after_detach.is_ok(),
                "ACP lost its session: {acp_after_detach:?}; SDK after ACP stop: {sdk_after_detach:?}; shutdown: {stopped:?}");
            assert!(sdk_after_detach.is_ok(),
                "ACP closed the SDK session: {sdk_after_detach:?}; shutdown: {stopped:?}");
            stopped.unwrap();
        })
    }).await;
}

#[tokio::test]
async fn acp_inherits_mcp_servers_from_its_runtime_host() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            std::fs::create_dir_all(ctx.home_dir()).unwrap();
            std::fs::write(
                ctx.home_dir().join("config.json"),
                json!({ "trustedFolders": [ctx.work_dir()] }).to_string(),
            )
            .unwrap();
            let harness = ctx.repo_root().join("test/harness");
            let owner = Client::start_with_host_configuration(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::new(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
                RuntimeHostConfiguration {
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
                },
            )
            .await
            .unwrap();
            let mut defaults = acp_defaults();
            defaults["allowAllEnv"] = json!("true");
            let (transport_id, mut reader, mut writer) =
                connect_acp(&owner, ctx, defaults).await;
            for (request_id, expected_tool) in
                [(2, Some("echo_meta")), (3, Some("host-current")), (4, None)]
            {
                if request_id != 2 {
                    let servers = expected_tool.map_or_else(
                        || json!({}),
                        |tool| json!({
                            "host-meta": {
                                "command": if cfg!(windows) { "node.exe" } else { "node" },
                                "args": [harness.join("test-mcp-server.mjs"), "--tool-name", tool],
                                "cwd": harness,
                                "tools": ["*"],
                            },
                        }),
                    );
                    owner
                        .configure_in_process_server(
                            json!({ "additionalMcpServers": servers })
                                .as_object()
                                .unwrap(),
                        )
                        .await
                        .unwrap();
                }
                let (created, _) = acp_request(
                    &mut reader,
                    &mut writer,
                    request_id,
                    "session/new",
                    json!({
                        "cwd": ctx.work_dir(),
                        "mcpServers": if request_id == 2 {
                            json!([{
                                "name": "host-meta",
                                "command": if cfg!(windows) { "node.exe" } else { "node" },
                                "args": [harness.join("test-mcp-server.mjs"), "--tool-name", "client-override"],
                                "env": [],
                            }])
                        } else {
                            json!([])
                        },
                    }),
                )
                .await;
                let session_id = created["sessionId"].as_str().unwrap();
                let servers = owner
                    .call(
                        "session.mcp.list",
                        Some(json!({
                            "sessionId": session_id,
                        })),
                    )
                    .await
                    .unwrap();
                let configured = servers["servers"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|server| server["name"] == "host-meta");
                let Some(expected_tool) = expected_tool else {
                    assert!(
                        !configured,
                        "ACP restored a removed host MCP server: {servers}"
                    );
                    continue;
                };
                let tools = if configured {
                    Some(
                        tokio::time::timeout(default_event_timeout(), async {
                            loop {
                                let state = owner
                                    .call(
                                        "session.mcp.list",
                                        Some(json!({
                                            "sessionId": session_id,
                                        })),
                                    )
                                    .await
                                    .unwrap();
                                let server = state["servers"]
                                    .as_array()
                                    .unwrap()
                                    .iter()
                                    .find(|server| server["name"] == "host-meta")
                                    .unwrap();
                                assert_ne!(server["status"], "failed", "host MCP failed: {server}");
                                if server["status"] == "connected" {
                                    let tools = owner
                                        .call(
                                            "session.mcp.listTools",
                                            Some(json!({
                                                "sessionId": session_id, "serverName": "host-meta",
                                            })),
                                        )
                                        .await
                                        .unwrap();
                                    assert!(
                                        tools["tools"]
                                            .as_array()
                                            .unwrap()
                                            .iter()
                                            .any(|tool| tool["name"] == expected_tool),
                                        "{tools}"
                                    );
                                    break tools;
                                }
                                tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                            }
                        })
                        .await
                        .expect("host MCP tools did not become ready"),
                    )
                } else {
                    None
                };
                assert!(
                    configured,
                    "ACP lost the host MCP server: {servers}; tools: {tools:?}"
                );
                if request_id == 2 {
                    let next_directory = ctx.work_dir().join("mcp-next-directory");
                    std::fs::create_dir(&next_directory).unwrap();
                    std::fs::write(
                        next_directory.join(".mcp.json"),
                        serde_json::to_vec(&json!({
                            "mcpServers": {
                                "directory-marker": {
                                    "command": if cfg!(windows) { "node.exe" } else { "node" },
                                    "args": [harness.join("test-mcp-server.mjs")],
                                    "cwd": harness,
                                    "tools": ["*"],
                                },
                            },
                        }))
                        .unwrap(),
                    )
                    .unwrap();
                    owner
                        .call(
                            "session.metadata.setWorkingDirectory",
                            Some(json!({
                                "sessionId": session_id,
                                "workingDirectory": next_directory,
                            })),
                        )
                        .await
                        .unwrap();
                    let mut last_state = Value::Null;
                    tokio::time::timeout(default_event_timeout(), async {
                        loop {
                            last_state = owner
                                .call("session.mcp.list", Some(json!({ "sessionId": session_id })))
                                .await
                                .unwrap();
                            let reloaded = last_state["servers"].as_array().unwrap();
                            if reloaded.iter().any(|server| {
                                server["name"] == "directory-marker"
                                    && server["status"] == "connected"
                            }) {
                                assert!(
                                    reloaded.iter().any(|server| server["name"] == "host-meta"),
                                    "the directory reload lost the host MCP server: {last_state}"
                                );
                                break;
                            }
                            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                        }
                    })
                    .await
                    .unwrap_or_else(|_| panic!("the directory MCP reload did not finish: {last_state}"));
                }
            }
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            owner.stop().await.unwrap();
        })
    })
    .await;
}

#[tokio::test]
async fn acp_sessions_use_the_owning_host_telemetry() {
    const CHILD: &str = "COPILOT_SDK_TEST_HOST_TELEMETRY_CHILD";
    if std::env::var_os(CHILD).is_none() {
        let output = tempfile::tempdir().unwrap();
        let mut command = tokio::process::Command::new(std::env::current_exe().unwrap());
        command
            .args([
                "--exact",
                "e2e_cases::host_transports::acp_sessions_use_the_owning_host_telemetry",
                "--nocapture",
            ])
            .current_dir(std::env::current_dir().unwrap())
            .env(CHILD, "1")
            .env("COPILOT_OTEL_ENABLED", "true")
            .env("COPILOT_OTEL_EXPORTER_TYPE", "file")
            .env(
                "COPILOT_OTEL_FILE_EXPORTER_PATH",
                output.path().join("telemetry.jsonl"),
            )
            .kill_on_drop(true);
        #[cfg(windows)]
        {
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }

        let completed = command.output().await.unwrap();
        assert!(
            completed.status.success()
                && String::from_utf8_lossy(&completed.stdout).contains("1 passed"),
            "isolated telemetry case:\n{}\n{}",
            String::from_utf8_lossy(&completed.stdout),
            String::from_utf8_lossy(&completed.stderr)
        );
        return;
    }
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            scripted_acp(
                ctx,
                json!([
                    { "requestIncludes": "ACP_OTEL_PROMPT", "content": "ACP_OTEL_REPLY" },
                    { "requestIncludes": "SDK_OTEL_PROMPT", "content": "SDK_OTEL_REPLY" },
                ]),
            );
            let telemetry = std::path::PathBuf::from(
                std::env::var_os("COPILOT_OTEL_FILE_EXPORTER_PATH").unwrap(),
            );
            let options = ctx
                .client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::default(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN));
            let owner = Client::start(options).await.unwrap();
            let mut defaults = acp_defaults();
            defaults["events"] = json!({ "streaming": true });
            let (transport_id, mut reader, mut writer) = connect_acp(&owner, ctx, defaults).await;
            let (created, _) = acp_request(
                &mut reader,
                &mut writer,
                2,
                "session/new",
                json!({
                    "cwd": ctx.work_dir(), "mcpServers": [],
                }),
            )
            .await;
            let acp_id = created["sessionId"].as_str().unwrap();
            acp_request(
                &mut reader,
                &mut writer,
                3,
                "session/prompt",
                json!({
                    "sessionId": acp_id,
                    "prompt": [{ "type": "text", "text": "ACP_OTEL_PROMPT" }],
                }),
            )
            .await;
            let control = owner
                .create_session(
                    ctx.approve_all_session_config()
                        .with_model("claude-sonnet-4.5")
                        .with_enable_config_discovery(false)
                        .with_github(|group| group.with_fetch_managed_settings(false))
                        .with_state(|group| group.with_enable_cross_session_store(true))
                        .with_name("SDK telemetry control"),
                )
                .await
                .unwrap();
            control.send_and_wait("SDK_OTEL_PROMPT").await.unwrap();
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            control.disconnect().await.unwrap();
            owner.stop().await.unwrap();
            let events =
                std::fs::read_to_string(&telemetry).expect("read the host telemetry export");
            let spans = events
                .lines()
                .filter(|line| !line.trim().is_empty())
                .map(|line| serde_json::from_str::<Value>(line).unwrap())
                .filter(|event| event["type"] == "span")
                .collect::<Vec<_>>();
            let has_session = |id: &str| {
                spans.iter().any(|span| {
                    span.pointer("/attributes/gen_ai.conversation.id")
                        .and_then(Value::as_str)
                        == Some(id)
                })
            };
            assert!(
                has_session(control.id().as_str()),
                "the SDK control produced no session spans; exported span count: {}",
                spans.len()
            );
            assert!(
                has_session(acp_id),
                "ACP produced no session spans; exported span count: {}",
                spans.len()
            );
            let index = rusqlite::Connection::open_with_flags(
                ctx.home_dir().join("session-store.db"),
                rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
            )
            .expect("open the runtime session index");
            let indexed = |id: &str| {
                index
                    .query_row("SELECT COUNT(*) FROM sessions WHERE id = ?1", [id], |row| {
                        row.get::<_, i64>(0)
                    })
                    .unwrap()
            };
            assert_eq!(
                indexed(control.id().as_str()),
                1,
                "the SDK control was not indexed"
            );
            assert_eq!(
                indexed(acp_id),
                1,
                "ACP was missing from the shared session index"
            );
        })
    })
    .await;
}

#[tokio::test]
async fn acp_transport_mcp_overrides_survive_trusted_workspace_resolution() {
    with_dedicated_e2e_context_no_snapshot(|ctx| {
        Box::pin(async move {
            ctx.set_default_copilot_user();
            let harness = ctx.repo_root().join("test/harness");
            let configuration = |tool: &str| {
                json!({
                    "command": if cfg!(windows) { "node.exe" } else { "node" },
                    "args": [harness.join("test-mcp-server.mjs"), "--tool-name", tool],
                    "cwd": harness,
                    "tools": ["*"],
                })
            };
            std::fs::write(
                ctx.work_dir().join(".mcp.json"),
                serde_json::to_vec(&json!({
                    "mcpServers": { "docs": configuration("workspace-default") },
                }))
                .unwrap(),
            )
            .unwrap();
            let owner = Client::start_with_host_configuration(
                ctx.client_options_with_transport(RuntimeConnection::InProcess(
                    InProcessRuntimeOptions::new(),
                ))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN)),
                RuntimeHostConfiguration {
                    additional_mcp_servers: Some(HashMap::from([(
                        "docs".to_owned(),
                        configuration("host-default"),
                    )])),
                    ..Default::default()
                },
            )
            .await
            .unwrap();
            let mut defaults = acp_defaults();
            defaults["allowAllEnv"] = json!("true");
            defaults["homeDirectory"] = json!(ctx.home_dir());
            defaults["mcp"] = json!({ "servers": { "docs": configuration("transport-override") } });
            defaults["mcp"]["servers"]["docs"]["source"] = Value::Null;
            let (transport_id, mut reader, mut writer) = connect_acp(&owner, ctx, defaults).await;
            let (created, _) = acp_request(
                &mut reader,
                &mut writer,
                2,
                "session/new",
                json!({
                    "cwd": ctx.work_dir(), "mcpServers": [],
                }),
            )
            .await;
            let id = created["sessionId"].as_str().unwrap();
            let mut last_state = Value::Null;
            let tools = tokio::time::timeout(default_event_timeout(), async {
                loop {
                    last_state = owner
                        .call("session.mcp.list", Some(json!({ "sessionId": id })))
                        .await
                        .unwrap();
                    let server = last_state["servers"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .find(|server| server["name"] == "docs")
                        .unwrap_or_else(|| panic!("missing docs server: {last_state}"));
                    assert_ne!(server["status"], "failed", "{server}");
                    if server["status"] == "connected" {
                        break owner
                            .call(
                                "session.mcp.listTools",
                                Some(json!({
                                    "sessionId": id, "serverName": "docs",
                                })),
                            )
                            .await
                            .unwrap();
                    }
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                }
            })
            .await
            .unwrap_or_else(|_| panic!("the MCP server did not connect; last state: {last_state}"));
            owner
                .rpc()
                .host()
                .transports()
                .stop(HostTransportRequest { transport_id })
                .await
                .unwrap();
            owner.stop().await.unwrap();
            let names = tools["tools"]
                .as_array()
                .unwrap()
                .iter()
                .map(|tool| &tool["name"])
                .collect::<Vec<_>>();
            assert!(
                names.iter().any(|name| **name == "transport-override"),
                "wrong MCP source: {names:?}"
            );
            assert!(
                !names.iter().any(|name| **name == "host-default"),
                "{names:?}"
            );
        })
    })
    .await;
}
