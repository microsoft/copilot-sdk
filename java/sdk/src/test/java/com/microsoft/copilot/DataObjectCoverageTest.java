/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.copilot.rpc.CustomAgentConfig;
import com.microsoft.copilot.rpc.McpHttpServerConfig;
import com.microsoft.copilot.rpc.McpStdioServerConfig;
import com.microsoft.copilot.rpc.PermissionRequest;
import com.microsoft.copilot.rpc.PreToolUseHookOutput;
import com.microsoft.copilot.rpc.ToolDefer;
import com.microsoft.copilot.rpc.ToolResultObject;

/**
 * Wire-contract tests for hand-written DTOs: hook/tool-result factory
 * discriminator strings, permission extension-data passthrough, and JSON field
 * names/omission for agent and MCP server configs.
 */
class DataObjectCoverageTest {

    @Test
    void preToolUseHookOutputDenyWithReason() {
        var output = PreToolUseHookOutput.deny("Security policy violation");
        assertEquals("deny", output.permissionDecision());
        assertEquals("Security policy violation", output.permissionDecisionReason());
        assertNull(output.modifiedArgs());
    }

    @Test
    void preToolUseHookOutputAsk() {
        var output = PreToolUseHookOutput.ask();
        assertEquals("ask", output.permissionDecision());
        assertNull(output.permissionDecisionReason());
    }

    @Test
    void preToolUseHookOutputWithModifiedArgs() {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "/safe/path");

        var output = PreToolUseHookOutput.withModifiedArgs("allow", args);
        assertEquals("allow", output.permissionDecision());
        assertEquals(args, output.modifiedArgs());
    }

    @Test
    void toolResultObjectErrorWithTextAndError() {
        var result = ToolResultObject.error("partial output", "File not found");
        assertEquals("error", result.resultType());
        assertEquals("partial output", result.textResultForLlm());
        assertEquals("File not found", result.error());
    }

    @Test
    void toolResultObjectFailure() {
        var result = ToolResultObject.failure("Tool unavailable", "Unknown tool");
        assertEquals("failure", result.resultType());
        assertEquals("Tool unavailable", result.textResultForLlm());
        assertEquals("Unknown tool", result.error());
    }

    @Test
    void permissionRequestPreservesMcpExtensionData() {
        var request = PermissionRequest.fromJsonValue(
                java.util.Map.of("kind", "mcp", "serverName", "playwright", "toolName", "playwright-browser_navigate",
                        "args", java.util.Map.of("url", "http://127.0.0.1:8106/docs/target-app/")));

        assertEquals("mcp", request.getKind());
        assertEquals("playwright", request.getExtensionData().get("serverName"));
        assertEquals("playwright-browser_navigate", request.getExtensionData().get("toolName"));
        @SuppressWarnings("unchecked")
        var args = (java.util.Map<String, Object>) request.getExtensionData().get("args");
        assertEquals("http://127.0.0.1:8106/docs/target-app/", args.get("url"));
    }

    @Test
    void permissionRequestWithoutExtensionDataPreservesNull() {
        var request = PermissionRequest.fromJsonValue(java.util.Map.of("kind", "read", "toolCallId", "tool-123"));

        assertNull(request.getExtensionData());
    }

    @Test
    void customAgentConfigModelAndReasoningEffortSerializationRoundTrip() throws Exception {
        var mapper = JsonRpcClient.getObjectMapper();
        var cfg = new CustomAgentConfig().setName("my-agent").setModel("claude-haiku-4.5").setReasoningEffort("high");

        var json = mapper.writeValueAsString(cfg);
        assertTrue(json.contains("\"model\":\"claude-haiku-4.5\""));
        assertTrue(json.contains("\"reasoningEffort\":\"high\""));

        var deserialized = mapper.readValue(json, CustomAgentConfig.class);
        assertEquals("my-agent", deserialized.getName());
        assertEquals("claude-haiku-4.5", deserialized.getModel());
        assertEquals("high", deserialized.getReasoningEffort());
    }

    @Test
    void mcpHttpServerConfigCoversGettersAndFluentSetters() {
        var headers = java.util.Map.of("Authorization", "Bearer token");
        var tools = java.util.List.of("*", "search");
        var oauthScopes = java.util.List.of("tools:read", "resources:read");

        var cfg = new McpHttpServerConfig().setUrl("https://mcp.example.com/sse").setHeaders(headers).setTools(tools)
                .setTimeout(45).setOauthClientId("static-client").setOauthScopes(oauthScopes).setOauthPublicClient(true)
                .setOauthGrantType("authorization_code");

        assertEquals("http", cfg.getType());
        assertEquals("https://mcp.example.com/sse", cfg.getUrl());
        assertEquals("Bearer token", cfg.getHeaders().get("Authorization"));
        assertEquals(tools, cfg.getTools());
        assertEquals(45, cfg.getTimeout());
        assertEquals("static-client", cfg.getOauthClientId());
        assertEquals(oauthScopes, cfg.getOauthScopes());
        assertTrue(cfg.getOauthPublicClient());
        assertEquals("authorization_code", cfg.getOauthGrantType());

        var json = new ObjectMapper().valueToTree(cfg);
        assertEquals("static-client", json.path("oauthClientId").asText());
        assertEquals("tools:read", json.path("oauthScopes").get(0).asText());
        assertEquals("resources:read", json.path("oauthScopes").get(1).asText());
        assertTrue(json.path("oauthPublicClient").asBoolean());
        assertEquals("authorization_code", json.path("oauthGrantType").asText());
    }

    @Test
    void mcpStdioServerConfigSerializesWorkingDirectoryAsCwd() {
        var json = new ObjectMapper()
                .valueToTree(new McpStdioServerConfig().setCommand("node").setWorkingDirectory("/workspace"));

        assertEquals("stdio", json.path("type").asText());
        assertEquals("/workspace", json.path("cwd").asText());
        assertFalse(json.has("workingDirectory"));
    }

    @Test
    void mcpServerConfigsSerializeDeferTools() {
        var mapper = JsonRpcClient.getObjectMapper();
        var stdio = new McpStdioServerConfig().setCommand("node").setDeferTools(ToolDefer.NEVER);
        var http = new McpHttpServerConfig().setUrl("https://mcp.example.com").setDeferTools(ToolDefer.AUTO);

        assertEquals(ToolDefer.NEVER, stdio.getDeferTools());
        assertEquals("never", mapper.valueToTree(stdio).path("deferTools").asText());
        assertEquals("auto", mapper.valueToTree(http).path("deferTools").asText());
        assertFalse(mapper.valueToTree(new McpStdioServerConfig().setCommand("node")).has("deferTools"));

        stdio.setDeferTools(ToolDefer.NONE);
        assertNull(stdio.getDeferTools());
        assertFalse(mapper.valueToTree(stdio).has("deferTools"));
    }

}
