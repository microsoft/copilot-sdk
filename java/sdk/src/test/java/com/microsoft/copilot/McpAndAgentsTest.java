/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import com.microsoft.copilot.rpc.SessionMcpOptions;
import com.microsoft.copilot.rpc.SessionPermissionOptions;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.generated.rpc.McpServerStatus;
import com.microsoft.copilot.rpc.DefaultAgentConfig;
import com.microsoft.copilot.rpc.McpServerConfig;
import com.microsoft.copilot.rpc.McpStdioServerConfig;
import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.ResumeSessionOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;
import com.microsoft.copilot.rpc.ToolDefinition;

/**
 * Tests for MCP Servers and Custom Agents functionality.
 *
 * <p>
 * These tests use the shared CapiProxy infrastructure for deterministic API
 * response replay. Snapshots are stored in test/snapshots/mcp_and_agents/.
 * </p>
 */
@Tag("e2e")
public class McpAndAgentsTest {

    private static E2ETestContext ctx;

    @BeforeAll
    static void setup() throws Exception {
        ctx = E2ETestContext.create();
    }

    @AfterAll
    static void teardown() throws Exception {
        if (ctx != null) {
            ctx.close();
        }
    }

    private Map<String, McpServerConfig> createTestMcpServers(String... serverNames) {
        Map<String, McpServerConfig> servers = new HashMap<>();
        for (String serverName : serverNames) {
            servers.put(serverName, createTestMcpServer());
        }
        return servers;
    }

    private McpStdioServerConfig createTestMcpServer() {
        Path harnessDir = ctx.getRepoRoot().resolve("test").resolve("harness");
        return new McpStdioServerConfig().setCommand("node")
                .setArgs(List.of(harnessDir.resolve("test-mcp-server.mjs").toString()))
                .setWorkingDirectory(harnessDir.toString()).setTools(List.of("*"));
    }

    private void waitForMcpServerStatus(CopilotSession session, String serverName, McpServerStatus expectedStatus)
            throws Exception {
        E2ETestContext.awaitReadiness(() -> session.getRpc().mcp.list(),
                result -> result.servers() != null && result.servers().stream()
                        .anyMatch(server -> serverName.equals(server.name()) && expectedStatus == server.status()),
                200, serverName + " did not reach " + expectedStatus);
    }

    // ============ MCP Server Tests ============

    /**
     * Verifies that MCP server configuration is accepted on session resume.
     *
     * @see Snapshot:
     *      mcp_and_agents/should_accept_mcp_server_configuration_on_session_resume
     */
    @Test
    @Timeout(value = 900, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SAME_THREAD)
    void testShouldAcceptMcpServerConfigurationOnSessionResume() throws Exception {
        ctx.configureForTest("mcp_and_agents", "should_accept_mcp_server_configuration_on_session_resume");

        try (CopilotClient client = ctx.createClient()) {
            // Create a session first
            CopilotSession session1 = client.createSession(ctx.newSessionConfig().setPermissions(
                    ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL))).get();
            String sessionId = session1.getSessionId();

            // Resume with MCP servers
            var mcpServers = createTestMcpServers("test-server");

            CopilotSession session2 = client.resumeSession(sessionId,
                    new ResumeSessionOptions().setMcp(new SessionMcpOptions().setServers(mcpServers))
                            .setPermissions(new SessionPermissionOptions().setOnRequest(PermissionHandler.APPROVE_ALL)))
                    .get();

            assertEquals(sessionId, session2.getSessionId());
            waitForMcpServerStatus(session2, "test-server", McpServerStatus.CONNECTED);

            session2.close();
        }
    }

    /**
     * Verifies that multiple MCP servers can be configured, including one whose
     * configuration omits args.
     *
     * @see Snapshot:
     *      mcp_and_agents/should_accept_mcp_server_configuration_on_session_create
     */
    @Test
    @Timeout(value = 900, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SAME_THREAD)
    void testShouldHandleMultipleMcpServersIncludingOneWithoutArgs() throws Exception {
        ctx.configureForTest("mcp_and_agents", "should_accept_mcp_server_configuration_on_session_create");

        var mcpServers = createTestMcpServers("server1", "server2");
        mcpServers.put("test-server", new McpStdioServerConfig().setCommand("echo").setTools(List.of("*")));

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(ctx.newSessionConfig()
                    .setMcp(ctx.newSessionConfig().getMcp().setServers(mcpServers)).setPermissions(
                            ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL)))
                    .get();

            assertNotNull(session.getSessionId());
            waitForMcpServerStatus(session, "server1", McpServerStatus.CONNECTED);
            waitForMcpServerStatus(session, "server2", McpServerStatus.CONNECTED);

            AssistantMessageEvent response = session.sendAndWait(new MessageOptions().setPrompt("What is 2+2?")).get(60,
                    TimeUnit.SECONDS);

            assertNotNull(response);
            assertTrue(response.getData().content().contains("4"),
                    "Response should contain 4: " + response.getData().content());
            session.close();
        }
    }

    // ============ DefaultAgent Tests ============

    /**
     * Verifies that sessions can be created with defaultAgent configuration and
     * excludedTools hides tools from the default agent.
     *
     * @see Snapshot: mcp_and_agents/should_hide_excluded_tools_from_default_agent
     */
    @Test
    void testShouldHideExcludedToolsFromDefaultAgent() throws Exception {
        ctx.configureForTest("mcp_and_agents", "should_hide_excluded_tools_from_default_agent");

        try (CopilotClient client = ctx.createClient()) {
            // Register a secret_tool and exclude it from the default agent — the LLM
            // should report it has no access to the tool.
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", Map.of("input", Map.of("type", "string")));
            parameters.put("required", List.of("input"));

            ToolDefinition secretTool = ToolDefinition.create("secret_tool",
                    "A secret tool hidden from the default agent", parameters,
                    invocation -> CompletableFuture.completedFuture("SECRET"));

            CreateSessionOptions config = ctx.newSessionConfig()
                    .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL))
                    .setTools(ctx.newSessionConfig().getTools().setDefinitions(List.of(secretTool)))
                    .setAgents(ctx.newSessionConfig().getAgents()
                            .setDefault(new DefaultAgentConfig().setExcludedTools(List.of("secret_tool"))));

            CopilotSession session = client.createSession(config).get();

            assertNotNull(session.getSessionId());

            AssistantMessageEvent response = session
                    .sendAndWait(new MessageOptions()
                            .setPrompt("Do you have access to a tool called secret_tool? Answer yes or no."))
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(response);
            assertTrue(response.getData().content().toLowerCase().contains("no"),
                    "Response should indicate that secret_tool is not accessible: " + response.getData().content());

            // The replay matcher ignores tools, so check the CAPI tool list directly.
            JsonNode exchanges = JsonRpcClient.getObjectMapper().valueToTree(ctx.getExchanges());
            assertFalse(exchanges.isEmpty(), "Should have at least one exchange");
            for (JsonNode exchange : exchanges) {
                for (JsonNode tool : exchange.path("request").path("tools")) {
                    assertNotEquals("secret_tool", tool.path("function").path("name").asText(), exchange.toString());
                }
            }
            session.close();
        }
    }

}
