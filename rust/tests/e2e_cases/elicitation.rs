use std::collections::VecDeque;
use std::sync::Arc;

use async_trait::async_trait;
use copilot_sdk::handler::{ElicitationHandler, PermissionHandler, PermissionResult};
use copilot_sdk::{
    CreateSessionOptions, ElicitationRequest, ElicitationResult, RequestId, SessionId,
    UiInputOptions,
};
use serde_json::json;
use tokio::sync::Mutex;

use super::support::DEFAULT_TEST_TOKEN;

#[tokio::test]
async fn elicitation_throws_when_capability_is_missing() {
    super::support::with_shared_e2e_context(
        &E2E,
        "elicitation",
        "elicitation_throws_when_capability_is_missing",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let session = client
                    .create_session(ctx.approve_all_session_config())
                    .await
                    .expect("create session");

                assert_ne!(
                    session.capabilities().ui.and_then(|ui| ui.elicitation),
                    Some(true)
                );
                assert!(session.ui().confirm("test").await.is_err());
                assert!(session.ui().select("test", &["a", "b"]).await.is_err());
                assert!(session.ui().input("test", None).await.is_err());
                assert!(
                    session
                        .ui()
                        .elicitation(
                            "Enter name",
                            json!({
                                "type": "object",
                                "properties": { "name": { "type": "string" } },
                                "required": ["name"]
                            }),
                        )
                        .await
                        .is_err()
                );

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

#[tokio::test]
async fn convenience_methods_map_handler_results() {
    super::support::with_shared_e2e_context(
        &E2E,
        "elicitation",
        "convenience_methods_map_handler_results",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let session = client
                    .create_session(
                        CreateSessionOptions::default()
                            .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN))
                            .pipe_handler(QueuedElicitationHandler::new([
                                accept(json!({ "confirmed": true })),
                                decline(),
                                accept(json!({ "selection": "beta" })),
                                accept(json!({ "value": "typed value" })),
                            ])),
                    )
                    .await
                    .expect("create session");

                assert_eq!(
                    session.capabilities().ui.and_then(|ui| ui.elicitation),
                    Some(true)
                );
                assert!(session.ui().confirm("Confirm?").await.expect("confirm"));
                assert!(!session.ui().confirm("Confirm?").await.expect("confirm"));
                assert_eq!(
                    session
                        .ui()
                        .select("Choose", &["alpha", "beta"])
                        .await
                        .expect("select")
                        .as_deref(),
                    Some("beta")
                );
                let options = UiInputOptions {
                    title: Some("Value"),
                    description: Some("A value to test"),
                    min_length: Some(1),
                    max_length: Some(20),
                    default: Some("default"),
                    ..UiInputOptions::default()
                };
                assert_eq!(
                    session
                        .ui()
                        .input("Enter value", Some(&options))
                        .await
                        .expect("input")
                        .as_deref(),
                    Some("typed value")
                );

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

#[tokio::test]
async fn elicitation_returns_all_action_shapes() {
    super::support::with_shared_e2e_context(
        &E2E,
        "elicitation",
        "elicitation_returns_all_action_shapes",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let session = client
                    .create_session(
                        CreateSessionOptions::default()
                            .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN))
                            .pipe_handler(QueuedElicitationHandler::new([
                                accept(json!({ "name": "Mona" })),
                                decline(),
                                cancel(),
                            ])),
                    )
                    .await
                    .expect("create session");
                let schema = json!({
                    "type": "object",
                    "properties": { "name": { "type": "string" } },
                    "required": ["name"]
                });

                let accepted = session
                    .ui()
                    .elicitation("Name?", schema.clone())
                    .await
                    .expect("accepted elicitation");
                let declined = session
                    .ui()
                    .elicitation("Name?", schema.clone())
                    .await
                    .expect("declined elicitation");
                let cancelled = session
                    .ui()
                    .elicitation("Name?", schema)
                    .await
                    .expect("cancelled elicitation");

                assert_eq!(accepted.action, "accept");
                assert_eq!(
                    accepted
                        .content
                        .and_then(|content| content.get("name").cloned()),
                    Some(json!("Mona"))
                );
                assert_eq!(declined.action, "decline");
                assert_eq!(cancelled.action, "cancel");

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

struct QueuedElicitationHandler {
    responses: Mutex<VecDeque<ElicitationResult>>,
}

impl QueuedElicitationHandler {
    fn new(responses: impl IntoIterator<Item = ElicitationResult>) -> Self {
        Self {
            responses: Mutex::new(responses.into_iter().collect()),
        }
    }
}

#[async_trait]
impl PermissionHandler for QueuedElicitationHandler {
    async fn handle(
        &self,
        _session_id: SessionId,
        _request_id: RequestId,
        _data: copilot_sdk::PermissionRequestData,
    ) -> PermissionResult {
        PermissionResult::approve_once()
    }
}

#[async_trait]
impl ElicitationHandler for QueuedElicitationHandler {
    async fn handle(
        &self,
        _session_id: SessionId,
        _request_id: RequestId,
        _request: ElicitationRequest,
    ) -> ElicitationResult {
        self.responses
            .lock()
            .await
            .pop_front()
            .expect("queued elicitation response")
    }
}

/// Test helper: install a single struct that implements both
/// [`PermissionHandler`] and [`ElicitationHandler`] on a [`CreateSessionOptions`].
trait PipeHandler {
    fn pipe_handler<H>(self, handler: H) -> Self
    where
        H: PermissionHandler + ElicitationHandler + 'static;
}

impl PipeHandler for CreateSessionOptions {
    fn pipe_handler<H>(self, handler: H) -> Self
    where
        H: PermissionHandler + ElicitationHandler + 'static,
    {
        let handler = Arc::new(handler);
        self.with_permissions(|group| {
            group.with_on_request(handler.clone() as Arc<dyn PermissionHandler>)
        })
        .with_interaction(|group| {
            group.with_on_elicitation_request(handler as Arc<dyn ElicitationHandler>)
        })
    }
}

fn accept(content: serde_json::Value) -> ElicitationResult {
    ElicitationResult {
        action: "accept".to_string(),
        content: Some(content),
    }
}

fn decline() -> ElicitationResult {
    ElicitationResult {
        action: "decline".to_string(),
        content: None,
    }
}

fn cancel() -> ElicitationResult {
    ElicitationResult {
        action: "cancel".to_string(),
        content: None,
    }
}
static E2E: super::support::SharedE2eGroup =
    super::support::SharedE2eGroup::standard("elicitation", 3);
