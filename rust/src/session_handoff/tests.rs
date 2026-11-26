#![cfg(test)]

use std::path::PathBuf;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use super::*;
use crate::jsonrpc::JsonRpcClient;

struct Handler {
    reached: mpsc::UnboundedSender<String>,
    progress_dropped: CancellationToken,
    confirmation_dropped: CancellationToken,
}

#[async_trait]
impl SessionHandoffHandler for Handler {
    async fn progress(&self, update: SessionHandoffProgressNotification) -> Result<()> {
        let _dropped =
            (update.callback_id == "blocked").then(|| self.progress_dropped.clone().drop_guard());
        self.reached.send(update.callback_id.clone()).unwrap();
        if update.callback_id == "blocked" {
            std::future::pending::<()>().await;
        }
        Ok(())
    }

    async fn confirm(&self, request: SessionHandoffConfirmationRequest) -> Result<bool> {
        if request.callback_id == "pending" {
            let _dropped = self.confirmation_dropped.clone().drop_guard();
            self.reached.send(request.callback_id).unwrap();
            std::future::pending::<()>().await;
        }
        Ok(true)
    }
}

#[test]
fn ordered_progress_does_not_block_confirmation_or_retain_the_client() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let (client, peer) = runtime.block_on(async {
        let (client_io, peer_io) = tokio::io::duplex(8192);
        let (read, write) = tokio::io::split(client_io);
        let client = Client::from_streams(
            crate::ClientMode::CodingAgent,
            read,
            write,
            PathBuf::from("."),
        )
        .unwrap();
        let (read, write) = tokio::io::split(peer_io);
        let (notifications, _notifications) = mpsc::unbounded_channel();
        let (requests, _requests) = mpsc::unbounded_channel();
        let peer = JsonRpcClient::new(write, read, notifications, requests);
        (client, peer)
    });
    let (reached, mut observed) = mpsc::unbounded_channel();
    let handler = Arc::new(Handler {
        reached,
        progress_dropped: CancellationToken::new(),
        confirmation_dropped: CancellationToken::new(),
    });
    client
        .register_session_handoff_handler(handler.clone())
        .unwrap();
    assert!(
        client
            .register_session_handoff_handler(handler.clone())
            .is_err()
    );
    runtime.block_on(async {
        tokio::time::timeout(Duration::from_secs(5), async {
        for id in ["first", "second", "blocked"] {
            peer.write(&json!({
                "jsonrpc": "2.0", "method": "sessionHandoff.progress",
                "params": { "callbackId": id, "update": { "step": "load-session", "status": "complete" } }
            })).await.unwrap();
        }
        for id in ["first", "second", "blocked"] {
            assert_eq!(observed.recv().await.as_deref(), Some(id));
        }
        let response = peer.send_request("sessionHandoff.confirm", Some(json!({
            "callbackId": "accepted", "localRepo": "local/repo", "remoteRepo": "remote/repo",
        }))).await.unwrap();
        assert_eq!(response.result, Some(json!({ "confirmed": true })));
        let pending = peer.begin_request_with_inline_callback("sessionHandoff.confirm", Some(json!({
            "callbackId": "pending", "localRepo": "local/repo", "remoteRepo": "remote/repo",
        })), None).unwrap();
        assert_eq!(observed.recv().await.as_deref(), Some("pending"));
        let owner = Arc::downgrade(&client.inner);
        drop(client);
        assert!(owner.upgrade().is_none());
        handler.progress_dropped.cancelled().await;
        handler.confirmation_dropped.cancelled().await;
        assert!(pending.await.is_err());
        peer.force_close();
        }).await.expect("handoff callbacks must settle when their client closes");
    });
}
