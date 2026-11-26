/* Copyright (c) Microsoft Corporation. All rights reserved. */

#![cfg(test)]

use super::*;

#[tokio::test]
async fn acp_shutdown_preserves_callback_scope_and_expires_after_the_handler_returns() {
    let home = tempfile::tempdir().unwrap();
    let (client_stream, server_stream) = tokio::io::duplex(8192);
    let (reader, writer) = tokio::io::split(client_stream);
    let (server_read, server_write) = tokio::io::split(server_stream);
    let client = Client::from_streams(
        ClientMode::CodingAgent,
        reader,
        writer,
        home.path().to_owned(),
    )
    .unwrap();
    let (notifications, _) = mpsc::unbounded_channel();
    let (requests, _) = mpsc::unbounded_channel();
    let server = jsonrpc::JsonRpcClient::new(server_write, server_read, notifications, requests);
    let (captured, mut methods) = mpsc::unbounded_channel();
    for method in ["acp.shutdown", "acp.requestShutdown", "runtime.shutdown"] {
        let captured = captured.clone();
        server
            .register_request_handler(
                method,
                Arc::new(move |_| {
                    captured.send(method).unwrap();
                    Box::pin(async { Ok(serde_json::Value::Null) })
                }),
            )
            .unwrap();
    }
    let (saved, scoped) = tokio::sync::oneshot::channel();
    let saved = Arc::new(parking_lot::Mutex::new(Some(saved)));
    client
        .register_request_handler("acp.telemetry", move |_, original| {
            let saved = saved.clone();
            async move {
                let error = original.call("runtime.shutdown", None).await.unwrap_err();
                assert_eq!(error.kind(), &ErrorKind::InvalidConfig);
                let error = match original.begin_call("runtime.shutdown", None) {
                    Ok(_) => panic!("runtime shutdown must not start inside ACP telemetry"),
                    Err(error) => error,
                };
                assert_eq!(error.kind(), &ErrorKind::InvalidConfig);
                let error = original
                    .call_typed::<serde::de::IgnoredAny>("runtime.shutdown", None)
                    .await
                    .unwrap_err();
                assert_eq!(error.kind(), &ErrorKind::InvalidConfig);
                original.call("acp.shutdown", None).await?;
                original.begin_call("acp.shutdown", None)?.await?;
                original
                    .call_typed::<serde::de::IgnoredAny>("acp.shutdown", None)
                    .await?;
                saved.lock().take().unwrap().send(original).unwrap();
                Ok(serde_json::json!({}))
            }
        })
        .unwrap();
    let result = tokio::time::timeout(Duration::from_secs(5), async {
        server
            .send_request(
                "acp.telemetry",
                Some(serde_json::json!({"action": "commit"})),
            )
            .await
            .unwrap();
        let original = scoped.await.unwrap();
        original.call("acp.shutdown", None).await.unwrap();
        client.call("acp.shutdown", None).await.unwrap();
        original.call("runtime.shutdown", None).await.unwrap();
        client.call("runtime.shutdown", None).await.unwrap();
        let mut observed = Vec::new();
        for _ in 0..7 {
            observed.push(methods.recv().await.unwrap());
        }
        assert_eq!(
            observed,
            [
                "acp.requestShutdown",
                "acp.requestShutdown",
                "acp.requestShutdown",
                "acp.shutdown",
                "acp.shutdown",
                "runtime.shutdown",
                "runtime.shutdown",
            ]
        );
    })
    .await;
    client.force_stop();
    server.force_close();
    result.unwrap();
}
