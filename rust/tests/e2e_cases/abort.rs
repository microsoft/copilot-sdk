use std::sync::Arc;

use async_trait::async_trait;
use copilot_sdk::handler::ApproveAllHandler;
use copilot_sdk::session_events::{SessionEventType, SessionInfoData};
use copilot_sdk::tool::ToolHandler;
use copilot_sdk::{CreateSessionOptions, Error, Tool, ToolInvocation, ToolResult};
use serde_json::json;
use tokio::sync::{Mutex, mpsc, oneshot};

use super::support::{
    DEFAULT_TEST_TOKEN, assistant_message_content, recv_with_timeout, wait_for_event,
};

#[tokio::test]
async fn should_abort_during_active_tool_execution() {
    super::support::with_dedicated_e2e_context(
        "abort",
        "should_abort_during_active_tool_execution",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let (started_tx, mut started_rx) = mpsc::unbounded_channel();
                let (release_tx, release_rx) = oneshot::channel();
                let slow_tool = Arc::new(SlowAnalysisTool {
                    started_tx,
                    release_rx: Mutex::new(Some(release_rx)),
                });
                let session = client
                    .create_session(
                        CreateSessionOptions::default()
                            .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN))
                            .with_permissions(|group| {
                                group.with_on_request(Arc::new(ApproveAllHandler))
                            })
                            .with_tools(|group| {
                                group.with_definitions(vec![
                                    Tool::new("slow_analysis")
                                        .with_description(
                                            "A slow analysis tool that blocks until released",
                                        )
                                        .with_parameters(json!({
                                            "type": "object",
                                            "properties": {
                                                "value": {
                                                    "type": "string",
                                                    "description": "Value to analyze"
                                                }
                                            },
                                            "required": ["value"]
                                        }))
                                        .with_handler(slow_tool),
                                ])
                            }),
                    )
                    .await
                    .expect("create session");
                let events = session.subscribe();
                let cancellation_events = session.subscribe();

                session
                    .send("Use slow_analysis with value 'test_abort'. Wait for the result.")
                    .await
                    .expect("send tool turn");

                let tool_value = recv_with_timeout(&mut started_rx, "slow tool start").await;
                assert_eq!(tool_value, "test_abort");

                session.abort().await.expect("abort session");
                // Observe cancellation while the tool is still held; dropping release_tx on
                // failure still unblocks the tool.
                let notice = wait_for_event(cancellation_events, "cancellation notice", |event| {
                    event
                        .typed_data::<SessionInfoData>()
                        .is_some_and(|data| data.info_type == "cancellation")
                })
                .await;
                assert_eq!(
                    notice
                        .typed_data::<SessionInfoData>()
                        .expect("info data")
                        .message,
                    "Operation cancelled by user"
                );
                let _ = release_tx.send("RELEASED_AFTER_ABORT".to_string());
                wait_for_event(events, "session.idle after abort", |event| {
                    event.parsed_type() == SessionEventType::SessionIdle
                })
                .await;

                let recovery = session
                    .send_and_wait("Say 'tool_abort_recovery_ok'.")
                    .await
                    .expect("send recovery")
                    .expect("assistant message");
                assert!(
                    assistant_message_content(&recovery)
                        .to_lowercase()
                        .contains("tool_abort_recovery_ok")
                );

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

struct SlowAnalysisTool {
    started_tx: mpsc::UnboundedSender<String>,
    release_rx: Mutex<Option<oneshot::Receiver<String>>>,
}

#[async_trait]
impl ToolHandler for SlowAnalysisTool {
    async fn call(&self, invocation: ToolInvocation) -> Result<ToolResult, Error> {
        let value = invocation
            .arguments
            .get("value")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default()
            .to_string();
        let _ = self.started_tx.send(value);
        let release_rx = self
            .release_rx
            .lock()
            .await
            .take()
            .expect("slow tool called once");
        let released = release_rx.await.unwrap_or_else(|_| "released".to_string());
        Ok(ToolResult::Text(released))
    }
}
