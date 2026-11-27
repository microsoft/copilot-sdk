/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

#![cfg(test)]

use std::future::Future;
use std::sync::atomic::AtomicUsize;
use std::sync::mpsc as blocking;
use std::time::Duration;

use parking_lot::Mutex;
use serde_json::{Value, json};

use super::*;
use crate::jsonrpc::MessageWriter;
use crate::{Client, ClientMode};

static NEXT_ID: AtomicU32 = AtomicU32::new(1000);
static PEERS: OnceLock<Mutex<HashMap<u32, Arc<Native>>>> = OnceLock::new();

#[cfg(copilot_cli_host)]
#[tokio::test]
async fn abandoned_native_preparation_releases_the_original_hosts_reservation() {
    use crate::native_cli::{self, OtelHostConfiguration, OtelSessionAdmission};

    let directory = tempfile::tempdir().unwrap();
    for response_delivered in [false, true] {
        let image = std::fs::canonicalize(
            std::env::var_os("COPILOT_RUNTIME_CDYLIB")
                .expect("this test requires the same-checkout runtime image"),
        )
        .unwrap();
        let library = load_library(&image).unwrap();
        let create = bind::<unsafe extern "C" fn() -> u32>(
            library,
            b"copilot_runtime_server_create\0",
            &image,
        )
        .unwrap();
        let remove = *bind::<unsafe extern "C" fn(u32) -> bool>(
            library,
            b"copilot_runtime_server_remove\0",
            &image,
        )
        .unwrap();
        let server = NativeOtelServer {
            id: unsafe { create() },
            remove,
        };
        assert_ne!(server.id, 0);
        let host = FfiHost::create(
            None,
            None,
            None,
            Vec::new(),
            vec![
                "--config-dir".into(),
                directory.path().to_str().unwrap().into(),
                "--disable-builtin-mcps".into(),
            ],
            FfiHostTarget {
                auth_owner: None,
                configuration: None,
                server_id: Some(server.id),
                library_path: Some(&image),
                session_binding: None,
                file_logging_path: None,
            },
        )
        .unwrap();
        let (mut reader, shared) = host.start().await.unwrap();
        shared.admit_cli_host().unwrap();
        let requests = Arc::new(Mutex::new(HashMap::<Value, Value>::new()));
        let recorded = requests.clone();
        let sender = shared.clone();
        let (forward, forwarded) = mpsc::unbounded_channel();
        let mut incoming = std::mem::replace(&mut reader.rx, forwarded);
        let (prepared, preparation) = tokio::sync::oneshot::channel();
        let (release, released) = tokio::sync::oneshot::channel();
        let (settled, mut settlements) = mpsc::unbounded_channel();
        let observed = requests.clone();
        let relay = tokio::spawn(async move {
            let mut gate = Some((prepared, released));
            while let Some(frame) = incoming.recv().await {
                let message = decode_frame(&frame);
                let request = message
                    .get("id")
                    .and_then(|id| observed.lock().get(id).cloned());
                if request.as_ref().and_then(|value| value["method"].as_str())
                    == Some("host.telemetry.prepare")
                    && let Some((prepared, released)) = gate.take()
                {
                    prepared.send(message.clone()).unwrap();
                    let _ = released.await;
                }
                if request.as_ref().and_then(|value| value["method"].as_str())
                    == Some("host.telemetry.rollback")
                {
                    settled.send(message.clone()).unwrap();
                }
                if forward.send(frame).is_err() {
                    break;
                }
            }
        });
        let client = Client::from_transport(
            reader,
            MessageWriter::Synchronous(Arc::new(move |frame| {
                let request = decode_frame(frame);
                if let Some(id) = request.get("id") {
                    recorded.lock().insert(id.clone(), request);
                }
                sender.write_frame(frame)
            })),
            None,
            directory.path().to_path_buf(),
            None,
            None,
            false,
            false,
            false,
            false,
            None,
            None,
            None,
            ClientMode::CodingAgent,
            None,
            None,
            false,
        )
        .unwrap();
        *client.inner.ffi_host.lock() = Some(shared.clone());
        let environment = vec![
            ("COPILOT_OTEL_ENABLED".into(), "true".into()),
            ("COPILOT_OTEL_EXPORTER_TYPE".into(), "file".into()),
            (
                "COPILOT_OTEL_FILE_EXPORTER_PATH".into(),
                directory.path().join("otel.jsonl").to_str().unwrap().into(),
            ),
        ];
        native_cli::initialize_otel(
            &client,
            &OtelHostConfiguration {
                service_version: "abandoned-preparation",
                local_telemetry: None,
                environment: &environment,
            },
        )
        .await
        .unwrap();
        let params = json!({
            "model": "local/model",
            "workingDirectory": directory.path(),
            "inference": {
                "providers": [{"name": "local", "type": "openai", "baseUrl": "http://127.0.0.1:1"}],
                "models": [{"provider": "local", "id": "model"}]
            }
        });
        let resident = client
            .call("session.create", Some(params.clone()))
            .await
            .unwrap();
        let mut params = params;
        params["sessionId"] = resident["sessionId"].clone();
        let admission = OtelSessionAdmission::Prepare {
            resume: true,
            params,
        };
        let mut pending = Box::pin(native_cli::admit_otel_session(&client, &admission));
        assert!(futures_util::poll!(&mut pending).is_pending());
        let preparation = preparation.await.unwrap();
        assert!(preparation.get("error").is_none(), "{preparation}");
        let id = preparation["result"]["preparationId"]
            .as_str()
            .unwrap()
            .to_owned();
        if response_delivered {
            release.send(()).unwrap();
            client.call("ping", None).await.unwrap();
            drop(pending);
        } else {
            drop(pending);
            release.send(()).unwrap();
        }
        client.call("ping", None).await.unwrap();
        let rollback = requests
            .lock()
            .values()
            .find(|request| {
                request["method"] == "host.telemetry.rollback"
                    && request["params"]["preparationId"] == id
            })
            .cloned();
        assert!(
            rollback.is_some(),
            "the SDK discarded preparation {id} without rollback; response_delivered={response_delivered}"
        );
        let settled = settlements.recv().await.unwrap();
        assert!(settled.get("error").is_none(), "{settled}");
        let stale = native_cli::admit_otel_session(
            &client,
            &OtelSessionAdmission::Commit { preparation_id: id },
        )
        .await
        .unwrap_err();
        assert!(
            stale
                .to_string()
                .contains("Unknown session telemetry preparation"),
            "{stale}"
        );
        native_cli::shutdown_otel(&client).await.unwrap();
        client.force_stop();
        drop(client);
        drop(shared);
        relay.await.unwrap();
    }
}

#[cfg(copilot_cli_host)]
struct NativeOtelServer {
    id: u32,
    remove: unsafe extern "C" fn(u32) -> bool,
}

#[cfg(copilot_cli_host)]
impl Drop for NativeOtelServer {
    fn drop(&mut self) {
        unsafe {
            (self.remove)(self.id);
        }
    }
}

#[cfg(copilot_cli_host)]
fn decode_frame(frame: &[u8]) -> Value {
    let start = frame
        .windows(4)
        .position(|bytes| bytes == b"\r\n\r\n")
        .unwrap()
        + 4;
    serde_json::from_slice(&frame[start..]).unwrap()
}

struct Native {
    frames: Mutex<Vec<Value>>,
    callback: usize,
    fail: AtomicBool,
    reply: AtomicBool,
    close_gate: Mutex<Option<(blocking::Sender<()>, blocking::Receiver<()>)>>,
    shutdown_gate: Mutex<Option<(blocking::Sender<()>, blocking::Receiver<()>)>>,
    shutdowns: AtomicUsize,
}

fn peers() -> &'static Mutex<HashMap<u32, Arc<Native>>> {
    PEERS.get_or_init(Mutex::default)
}

fn wait_at(gate: &Mutex<Option<(blocking::Sender<()>, blocking::Receiver<()>)>>) {
    let wait = gate.lock().take();
    if let Some((entered, release)) = wait {
        let _ = entered.send(());
        let _ = release.recv();
    }
}

unsafe extern "C" fn write(connection: u32, bytes: *const u8, length: usize) -> bool {
    let Some(native) = peers().lock().get(&connection).cloned() else {
        return false;
    };
    let frame = unsafe { std::slice::from_raw_parts(bytes, length) };
    let Some(separator) = frame.windows(4).position(|bytes| bytes == b"\r\n\r\n") else {
        return false;
    };
    let Ok(value) = serde_json::from_slice::<Value>(&frame[separator + 4..]) else {
        return false;
    };
    native.frames.lock().push(value.clone());
    if native.fail.load(Ordering::SeqCst) {
        return false;
    }
    if native.reply.load(Ordering::SeqCst)
        && let Some(id) = value["id"].as_u64()
    {
        let response = json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": {"method": value["method"]},
        });
        let Ok(body) = serde_json::to_vec(&response) else {
            return false;
        };
        let mut frame = format!("Content-Length: {}\r\n\r\n", body.len()).into_bytes();
        frame.extend(body);
        on_outbound(native.callback as *mut c_void, frame.as_ptr(), frame.len());
    }
    true
}

unsafe extern "C" fn close(connection: u32) -> bool {
    let native = peers().lock().get(&connection).cloned();
    if let Some(native) = native {
        wait_at(&native.close_gate);
    }
    true
}

unsafe extern "C" fn shutdown(server: u32) -> bool {
    let native = peers().lock().get(&server).cloned();
    if let Some(native) = native {
        native.shutdowns.fetch_add(1, Ordering::SeqCst);
        wait_at(&native.shutdown_gate);
    }
    true
}

unsafe extern "C" fn configure_ui(_connection: u32, _enabled: bool) -> bool {
    true
}

unsafe extern "C" fn run_ui(_connection: u32, _action: u32) -> bool {
    true
}

pub(crate) struct Peer {
    client: Option<Client>,
    shared: Arc<FfiShared>,
    native: Arc<Native>,
    id: u32,
}

impl Peer {
    fn new() -> Self {
        Self::with_ownership(true)
    }

    pub(crate) fn with_ownership(owns_host: bool) -> Self {
        let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = mpsc::unbounded_channel();
        let callback = Box::into_raw(Box::new(CallbackState {
            tx: Mutex::new(Some(tx)),
            closing: AtomicBool::new(false),
        }));
        let native = Arc::new(Native {
            frames: Mutex::new(Vec::new()),
            callback: callback as usize,
            fail: AtomicBool::new(false),
            reply: AtomicBool::new(true),
            close_gate: Mutex::new(None),
            shutdown_gate: Mutex::new(None),
            shutdowns: AtomicUsize::new(0),
        });
        peers().lock().insert(id, native.clone());
        let shared = Arc::new(FfiShared {
            #[cfg(copilot_cli_host)]
            library: super::tests::library_without_private_admission(),
            auth_owner: Mutex::new(None),
            host_shutdown: shutdown,
            owns_host,
            connection_write: write,
            connection_close: close,
            connection_close_and_wait: None,
            connection_set_ui_thread_handler: configure_ui,
            connection_run_ui_action: run_ui,
            server_id: AtomicU32::new(id),
            connection_id: AtomicU32::new(id),
            callback_state: AtomicPtr::new(callback),
            closed: AtomicBool::new(false),
            operation_lock: Mutex::new(()),
            foreground_order: Arc::new(tokio::sync::Mutex::new(())),
            protocol_peers: Mutex::new(Some(Vec::new())),
            cleanup_done: tokio::sync::watch::channel(CleanupStatus::Pending).0,
            library_path: PathBuf::from("test-ingress-runtime"),
        });
        let sender = shared.clone();
        let client = Client::from_transport(
            FfiReader {
                rx,
                leftover: Vec::new(),
                pos: 0,
            },
            MessageWriter::Synchronous(Arc::new(move |frame| sender.write_frame(frame))),
            None,
            PathBuf::from("."),
            None,
            None,
            false,
            false,
            false,
            false,
            None,
            None,
            None,
            ClientMode::CodingAgent,
            None,
            None,
            false,
        )
        .unwrap();
        *client.inner.ffi_host.lock() = Some(shared.clone());
        Self {
            client: Some(client),
            shared,
            native,
            id,
        }
    }

    pub(crate) fn client(&self) -> &Client {
        self.client.as_ref().unwrap()
    }

    #[cfg(copilot_cli_host)]
    pub(crate) fn shutdowns(&self) -> usize {
        self.native.shutdowns.load(Ordering::SeqCst)
    }
}

impl Drop for Peer {
    fn drop(&mut self) {
        self.client.take();
        self.shared.close();
        peers().lock().remove(&self.id);
    }
}

fn completion(
    future: impl Future<Output = crate::Result<Value>> + Send + 'static,
) -> impl Future<Output = crate::Result<Value>> + Send + 'static {
    future
}

#[tokio::test]
async fn public_begin_call_reaches_c_abi_before_return_and_regular_call_stays_lazy() {
    let peer = Peer::new();
    let lazy = peer.client().call("lazy", None);
    assert!(peer.native.frames.lock().is_empty());
    drop(lazy);

    let method = String::from("first");
    let first = completion(peer.client().begin_call(&method, None).unwrap());
    drop(method);
    let second = completion(peer.client().begin_call("second", None).unwrap());
    let methods: Vec<_> = peer
        .native
        .frames
        .lock()
        .iter()
        .map(|frame| frame["method"].clone())
        .collect();
    assert_eq!(methods, [json!("first"), json!("second")]);
    assert_eq!(first.await.unwrap(), json!({"method": "first"}));
    assert_eq!(second.await.unwrap(), json!({"method": "second"}));
}

#[tokio::test]
async fn public_begin_call_reports_native_write_error_immediately() {
    let peer = Peer::new();
    peer.native.fail.store(true, Ordering::SeqCst);
    let error = match peer.client().begin_call("failed", None) {
        Ok(_) => panic!("native rejection must not return a completion future"),
        Err(error) => error,
    };
    assert_eq!(error.kind(), &crate::ErrorKind::Io);
    assert_eq!(peer.native.frames.lock().len(), 1);
}

#[tokio::test]
async fn completion_does_not_keep_client_or_connection_alive() {
    for method in ["pending", "session.sendAndWait"] {
        let mut peer = Peer::new();
        peer.native.reply.store(false, Ordering::SeqCst);
        let client = peer.client.take().unwrap();
        let weak = Arc::downgrade(&client.inner);
        let pending = completion(client.begin_call(method, None).unwrap());
        drop(client);
        assert!(weak.upgrade().is_none());
        assert!(peer.shared.closed.load(Ordering::SeqCst));
        assert!(
            tokio::time::timeout(Duration::from_secs(5), pending)
                .await
                .unwrap()
                .is_err()
        );
    }
}

struct Release(Option<blocking::Sender<()>>);

impl Drop for Release {
    fn drop(&mut self) {
        if let Some(sender) = self.0.take() {
            let _ = sender.send(());
        }
    }
}

fn assert_submission_does_not_wait_for_native_close(shutdown_phase: bool) {
    let peer = Peer::new();
    let (entered, observed) = blocking::channel();
    let (release, wait) = blocking::channel();
    let gate = if shutdown_phase {
        &peer.native.shutdown_gate
    } else {
        &peer.native.close_gate
    };
    *gate.lock() = Some((entered, wait));
    let release = Release(Some(release));
    let shared = peer.shared.clone();
    let closing = std::thread::spawn(move || shared.close());
    let entered = observed.recv_timeout(Duration::from_secs(5));
    if entered.is_err() {
        drop(release);
        closing.join().unwrap();
        panic!("native close did not reach its controlled gate: {entered:?}");
    }
    let client = peer.client().clone();
    let (finished, result) = blocking::channel();
    let submitting = std::thread::spawn(move || {
        let failed = client.begin_call("during-close", None).is_err();
        let _ = finished.send(failed);
    });
    let before_release = result.recv_timeout(Duration::from_secs(5));
    drop(release);
    closing.join().unwrap();
    submitting.join().unwrap();
    assert_eq!(
        before_release,
        Ok(true),
        "submission waited for native close or shutdown"
    );
    assert!(peer.native.frames.lock().is_empty());
}

#[tokio::test]
async fn synchronous_submission_does_not_wait_for_native_teardown() {
    for shutdown_phase in [false, true] {
        assert_submission_does_not_wait_for_native_close(shutdown_phase);
    }
}
