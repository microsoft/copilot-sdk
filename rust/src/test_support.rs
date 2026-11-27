// Copyright (c) Microsoft Corporation. All rights reserved.

//! Re-exported test helpers and JSON-RPC internals for unit and integration tests.

#![cfg(any(test, feature = "test-support"))]

use super::*;

#[cfg(feature = "test-support")]
pub use crate::jsonrpc::{
    JsonRpcClient, JsonRpcMessage, JsonRpcNotification, JsonRpcRequest, JsonRpcResponse,
    error_codes,
};

/// Build the inputs to [`CopilotRequestHandler::send_request`] without starting a runtime.
/// Enable `test-support` on your `copilot-sdk` dev-dependency, keeping
/// `default-features = false` if your normal dependency disables defaults.
///
/// The request and context start with identical URLs and headers and share a fresh
/// cancellation token. Optional context fields start as `None`; set them before
/// calling the handler to model a particular session or agent invocation.
/// For example, to test a handler that echoes request headers:
///
/// ```
/// use copilot_sdk::{CopilotRequestHandler, test_support::http_handler_inputs};
/// # use copilot_sdk::{CopilotHttpRequest, CopilotHttpResponse, CopilotRequestContext,
/// #     CopilotRequestError, CopilotRequestTransport,
/// #     CopilotWebSocketHandler, CopilotWebSocketResponse};
/// # struct Handler;
/// # #[async_trait::async_trait]
/// # impl CopilotRequestHandler for Handler {
/// #     async fn send_request(&self, req: CopilotHttpRequest, ctx: &CopilotRequestContext)
/// #         -> Result<CopilotHttpResponse, CopilotRequestError> {
/// #         assert_eq!(ctx.transport, CopilotRequestTransport::Http);
/// #         assert_eq!(ctx.agent_invocation_id.as_deref(), Some("turn-1"));
/// #         assert_eq!(req.body, b"{}");
/// #         assert_eq!(req.url, ctx.url);
/// #         assert_eq!(req.headers, ctx.headers);
/// #         Ok(CopilotHttpResponse::new(200, None, req.headers, Box::pin(futures_util::stream::empty())))
/// #     }
/// #     async fn open_websocket(&self, _: &CopilotRequestContext, _: CopilotWebSocketResponse)
/// #         -> Result<Box<dyn CopilotWebSocketHandler>, CopilotRequestError> { unreachable!() }
/// # }
/// # #[tokio::main(flavor = "current_thread")]
/// # async fn main() -> Result<(), CopilotRequestError> {
/// # let handler = Handler;
/// let mut headers = http::HeaderMap::new();
/// headers.insert("x-route", http::HeaderValue::from_static("chat"));
/// let (request, mut ctx) = http_handler_inputs(
///     "req-1", "POST", "https://example.test/chat", headers, b"{}",
/// );
/// ctx.agent_invocation_id = Some("turn-1".into());
/// let response = handler.send_request(request, &ctx).await?;
/// assert_eq!(response.headers["x-route"], "chat");
/// # let (next_request, next_ctx) = http_handler_inputs("req-2", "GET", "https://example.test", http::HeaderMap::new(), []);
/// # next_ctx.cancel.cancel();
/// # assert!(next_request.cancel.is_cancelled());
/// # assert!(!ctx.cancel.is_cancelled());
/// # Ok(())
/// # }
/// ```
pub fn http_handler_inputs(
    request_id: impl Into<String>,
    method: impl Into<String>,
    url: impl Into<String>,
    headers: http::HeaderMap,
    body: impl Into<Vec<u8>>,
) -> (CopilotHttpRequest, CopilotRequestContext) {
    let request = CopilotHttpRequest {
        method: method.into(),
        url: url.into(),
        headers,
        body: body.into(),
        cancel: tokio_util::sync::CancellationToken::new(),
    };
    let context = CopilotRequestContext {
        request_id: request_id.into(),
        session_id: None,
        agent_id: None,
        agent_invocation_id: None,
        parent_agent_id: None,
        interaction_type: None,
        transport: CopilotRequestTransport::Http,
        url: request.url.clone(),
        headers: request.headers.clone(),
        cancel: request.cancel.clone(),
    };
    (request, context)
}

/// Test fixture helpers for spawning a controlled Node.js shutdown process.
#[doc(hidden)]
#[allow(dead_code)]
pub mod node_fixture;

impl Client {
    /// Construct a client with declared session filesystem capabilities for
    /// framed transport tests, without starting a CLI process.
    #[doc(hidden)]
    pub fn from_streams_with_session_fs_config(
        reader: impl AsyncRead + Unpin + Send + 'static,
        writer: impl AsyncWrite + Unpin + Send + 'static,
        cwd: PathBuf,
        config: SessionFsConfig,
    ) -> Result<Self> {
        let sqlite = config.capabilities.as_ref().is_some_and(|c| c.sqlite);
        let binary = config.capabilities.as_ref().is_some_and(|c| c.binary);
        let read_file_range = config
            .capabilities
            .as_ref()
            .is_some_and(|c| c.read_file_range);
        Self::from_transport(
            reader,
            jsonrpc::MessageWriter::stream(writer),
            None,
            cwd,
            None,
            None,
            true,
            sqlite,
            binary,
            read_file_range,
            None,
            None,
            None,
            ClientMode::CodingAgent,
            None,
            None,
            false,
        )
    }

    /// Construct a [`Client`] from raw streams with a preset extension launch
    /// provider, for integration testing connection-global reverse requests.
    #[doc(hidden)]
    pub fn from_streams_with_extension_launch_provider(
        reader: impl AsyncRead + Unpin + Send + 'static,
        writer: impl AsyncWrite + Unpin + Send + 'static,
        cwd: PathBuf,
        provider: Arc<dyn crate::extension_launch_provider::ExtensionLaunchProvider>,
    ) -> Result<Self> {
        Self::from_transport(
            reader,
            jsonrpc::MessageWriter::stream(writer),
            None,
            cwd,
            None,
            Some(provider),
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
    }

    /// Construct a client with a confirmation handler for framed transport tests.
    #[doc(hidden)]
    pub fn from_streams_with_installation_confirmation_handler(
        reader: impl AsyncRead + Unpin + Send + 'static,
        writer: impl AsyncWrite + Unpin + Send + 'static,
        cwd: PathBuf,
        handler: Arc<dyn crate::installation_confirmation::InstallationConfirmationHandler>,
    ) -> Result<Self> {
        let client = Self::from_streams(ClientMode::CodingAgent, reader, writer, cwd)?;
        client
            .inner
            .installation_confirmation
            .set_handler(Some(handler));
        Ok(client)
    }

    /// Construct a [`Client`] from raw streams with a
    /// [`TraceContextProvider`] preset, for integration testing.
    ///
    /// Mirrors [`from_streams`](Self::from_streams) but exposes the
    /// `on_get_trace_context` plumbing so tests can verify outbound
    /// `traceparent` / `tracestate` injection on `session.create`,
    /// `session.resume`, and `session.send`.
    pub fn from_streams_with_trace_provider(
        reader: impl AsyncRead + Unpin + Send + 'static,
        writer: impl AsyncWrite + Unpin + Send + 'static,
        cwd: PathBuf,
        provider: Arc<dyn TraceContextProvider>,
    ) -> Result<Self> {
        Self::from_transport(
            reader,
            jsonrpc::MessageWriter::stream(writer),
            None,
            cwd,
            None,
            None,
            false,
            false,
            false,
            false,
            Some(provider),
            None,
            None,
            ClientMode::CodingAgent,
            None,
            None,
            false,
        )
    }

    /// Construct a [`Client`] from raw streams with a preset
    /// `effective_connection_token`, for integration testing the
    /// `connect` handshake's token-forwarding path.
    pub fn from_streams_with_connection_token(
        reader: impl AsyncRead + Unpin + Send + 'static,
        writer: impl AsyncWrite + Unpin + Send + 'static,
        cwd: PathBuf,
        token: Option<String>,
    ) -> Result<Self> {
        Self::from_transport(
            reader,
            jsonrpc::MessageWriter::stream(writer),
            None,
            cwd,
            None,
            None,
            false,
            false,
            false,
            false,
            None,
            None,
            token,
            ClientMode::CodingAgent,
            None,
            None,
            false,
        )
    }

    /// Construct a [`Client`] from raw streams with a preset GitHub telemetry
    /// callback, for integration testing telemetry forwarding.
    #[doc(hidden)]
    pub fn from_streams_with_github_telemetry(
        reader: impl AsyncRead + Unpin + Send + 'static,
        writer: impl AsyncWrite + Unpin + Send + 'static,
        cwd: PathBuf,
        on_github_telemetry: crate::github_telemetry::GitHubTelemetryCallback,
    ) -> Result<Self> {
        Self::from_transport(
            reader,
            jsonrpc::MessageWriter::stream(writer),
            None,
            cwd,
            None,
            None,
            false,
            false,
            false,
            false,
            None,
            Some(on_github_telemetry),
            None,
            ClientMode::CodingAgent,
            None,
            None,
            false,
        )
    }

    /// Public test-only wrapper around the random connection-token
    /// generator used by [`Client::start`] when the SDK spawns a TCP
    /// server without an explicit token. Lets integration tests
    /// validate the token shape (32-char lowercase hex, 128 bits of
    /// entropy) without re-implementing the helper.
    pub fn generate_connection_token_for_test() -> String {
        generate_connection_token()
    }

    /// Construct a [`Client`] from raw streams with a preset
    /// [`ClientInfo`], for integration testing the `connect` handshake's
    /// application-identity forwarding path.
    #[doc(hidden)]
    pub fn from_streams_with_client_info(
        reader: impl AsyncRead + Unpin + Send + 'static,
        writer: impl AsyncWrite + Unpin + Send + 'static,
        cwd: PathBuf,
        client_info: Option<ClientInfo>,
    ) -> Result<Self> {
        Self::from_transport(
            reader,
            jsonrpc::MessageWriter::stream(writer),
            None,
            cwd,
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
            client_info,
            None,
            false,
        )
    }

    /// Start this client's notification and request router on the current runtime.
    /// This is test-harness plumbing, not part of the supported SDK API.
    #[cfg(feature = "test-support")]
    #[doc(hidden)]
    pub fn start_router_for_test(&self) {
        self.inner.router.ensure_started(&self.inner);
    }

    /// Snapshot the session IDs currently registered on this client's
    /// notification router. This is test-harness plumbing, not part of the
    /// supported SDK API.
    #[cfg(feature = "test-support")]
    #[doc(hidden)]
    pub fn registered_session_ids_for_test(&self) -> Vec<SessionId> {
        self.inner.router.session_ids()
    }

    /// Count the sessions currently registered on this client's notification
    /// router. Deliberately never materialises the session IDs themselves so
    /// they cannot leak into test diagnostics.
    #[doc(hidden)]
    pub fn registered_session_count_for_test(&self) -> usize {
        self.inner.router.session_count()
    }

    /// Disconnect and delete every session owned by this test client's isolated
    /// runtime. This is test-harness plumbing, not part of the supported SDK API.
    #[cfg(feature = "test-support")]
    #[doc(hidden)]
    pub async fn cleanup_sessions_for_test(&self) -> Result<()> {
        let mut first_error = None;

        for session_id in self.inner.router.session_ids() {
            if let Err(error) = self.detach_session(&session_id, None).await
                && first_error.is_none()
            {
                first_error = Some(error);
            }
            self.inner.router.unregister(&session_id);
        }
        self.inner.github_token_registry.clear();

        match self.list_sessions(None).await {
            Ok(sessions) => {
                for session in sessions {
                    if let Err(error) = self.delete_session(&session.session_id).await
                        && first_error.is_none()
                    {
                        first_error = Some(error);
                    }
                }
            }
            Err(error) if first_error.is_none() => first_error = Some(error),
            Err(_) => {}
        }

        match first_error {
            Some(error) => Err(error),
            None => Ok(()),
        }
    }
}
