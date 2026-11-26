//! Connection-owned callbacks for remote-to-local session handoff.

use std::sync::Arc;

use async_trait::async_trait;
use futures_util::FutureExt;
use serde_json::{Value, json};
use tokio::sync::mpsc;

use crate::rpc::{SessionHandoffConfirmationRequest, SessionHandoffProgressNotification};
use crate::{Client, Result};

/// Observes handoff progress and confirms repository mismatches.
///
/// Register before calling `sessions.open` with a `callbackId`. The ID identifies
/// the request when several handoffs share a connection. Confirmations may run
/// concurrently. Progress arrives in order on a separate task, so it cannot block
/// RPC responses or confirmations. Connection closure cancels callback futures.
#[async_trait]
pub trait SessionHandoffHandler: Send + Sync + 'static {
    /// Observe one step without controlling the runtime operation.
    async fn progress(&self, _update: SessionHandoffProgressNotification) -> Result<()> {
        Ok(())
    }

    /// Confirm a repository mismatch. The default refuses the handoff.
    async fn confirm(&self, _request: SessionHandoffConfirmationRequest) -> Result<bool> {
        Ok(false)
    }
}

impl Client {
    /// Install this connection's handoff callbacks. Duplicate registration fails.
    ///
    /// Callbacks do not retain the client. Avoid capturing a client clone in the
    /// handler, which would create an ownership cycle.
    pub fn register_session_handoff_handler(
        &self,
        handler: Arc<dyn SessionHandoffHandler>,
    ) -> Result<()> {
        let confirmation = handler.clone();
        self.inner.rpc.register_request_handler(
            "sessionHandoff.confirm",
            Arc::new(move |params| {
                let handler = confirmation.clone();
                Box::pin(async move {
                    let request = serde_json::from_value(params)?;
                    Ok(json!({ "confirmed": handler.confirm(request).await? }))
                })
            }),
        )?;
        let (sender, mut updates) = mpsc::unbounded_channel::<Value>();
        self.inner
            .handoff_progress
            .set(sender)
            .expect("only the first callback registration installs the progress sender");
        let closed = self.inner.rpc.connection_closed_token();
        self.inner.runtime_handle.spawn(async move {
            loop {
                let params = tokio::select! {
                    biased;
                    () = closed.cancelled() => break,
                    params = updates.recv() => match params {
                        Some(params) => params,
                        None => break,
                    },
                };
                let update = match serde_json::from_value(params) {
                    Ok(update) => update,
                    Err(error) => {
                        tracing::warn!(%error, "Invalid sessionHandoff.progress notification");
                        continue;
                    }
                };
                let result = tokio::select! {
                    biased;
                    () = closed.cancelled() => break,
                    result = std::panic::AssertUnwindSafe(handler.progress(update)).catch_unwind() => result,
                };
                match result {
                    Ok(Ok(())) => {}
                    Ok(Err(error)) => tracing::warn!(%error, "Handoff progress callback failed"),
                    Err(_) => tracing::warn!("Handoff progress callback panicked"),
                }
            }
        });
        Ok(())
    }
}

#[cfg(test)]
mod tests;
