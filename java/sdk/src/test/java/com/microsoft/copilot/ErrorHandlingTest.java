/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.logging.Logger;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.microsoft.copilot.generated.SessionEvent;
import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.generated.SessionIdleEvent;
import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.ToolDefinition;

import java.util.Map;

/**
 * E2E tests for error handling scenarios.
 * <p>
 * These tests verify that the SDK properly handles errors in various scenarios
 * including tool errors, permission handler errors, and session errors.
 * </p>
 */
@Tag("e2e")
public class ErrorHandlingTest {

    private static final Logger LOG = Logger.getLogger(ErrorHandlingTest.class.getName());
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

    /**
     * Verifies that tool errors are handled gracefully and don't crash the session.
     *
     * @see Snapshot: tools/handles_tool_calling_errors
     */
    @Test
    void testHandlesToolCallingErrors_toolErrorDoesNotCrashSession() throws Exception {
        LOG.info("Running test: testHandlesToolCallingErrors_toolErrorDoesNotCrashSession");
        ctx.configureForTest("tools", "handles_tool_calling_errors");

        var allEvents = new CopyOnWriteArrayList<SessionEvent>();
        var idleReceived = new CompletableFuture<Void>();

        ToolDefinition errorTool = ToolDefinition.create("get_user_location", "Gets the user's location",
                Map.of("type", "object", "properties", Map.of()), (invocation) -> {
                    CompletableFuture<Object> future = new CompletableFuture<>();
                    future.completeExceptionally(new RuntimeException("Location service unavailable"));
                    return future;
                });

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(ctx.newSessionConfig()
                    .setTools(ctx.newSessionConfig().getTools().setDefinitions(List.of(errorTool))).setPermissions(
                            ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL)))
                    .get();

            session.on(event -> {
                allEvents.add(event);
                if (event instanceof SessionIdleEvent) {
                    idleReceived.complete(null);
                }
            });

            AssistantMessageEvent response = session
                    .sendAndWait(new MessageOptions()
                            .setPrompt("What is my location? If you can't find out, just say 'unknown'."))
                    .get(60, TimeUnit.SECONDS);
            // Await this listener, not just sendAndWait's internal idle listener.
            idleReceived.get(60, TimeUnit.SECONDS);

            // Session should complete without crashing
            assertNotNull(response, "Should receive a response even when tool fails");

            // Should have received session.idle (indicating successful completion)
            assertTrue(allEvents.stream().anyMatch(e -> e instanceof SessionIdleEvent),
                    "Session should reach idle state after handling tool error");

            session.close();
        }
    }

    /**
     * Verifies that returning a failure result from a tool is handled properly.
     *
     * @see Snapshot: tools/handles_tool_calling_errors
     */

    /**
     * Verifies that permission handler errors result in denied permission.
     *
     * @see Snapshot: permissions/should_handle_permission_handler_errors_gracefully
     */

    /**
     * Verifies that a permission handler exception denies the tool without failing
     * the session.
     *
     * @see Snapshot: permissions/should_handle_permission_handler_errors_gracefully
     */
    @Test
    void testPermissionHandlerErrors_sessionCompletesAfterDenial() throws Exception {
        LOG.info("Running test: testPermissionHandlerErrors_sessionCompletesAfterDenial");
        ctx.configureForTest("permissions", "should_handle_permission_handler_errors_gracefully");

        var permissionRequests = new AtomicInteger();
        var config = ctx.newSessionConfig()
                .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest((request, invocation) -> {
                    permissionRequests.incrementAndGet();
                    throw new RuntimeException("Test error message");
                }));

        try (CopilotClient client = ctx.createClient(); CopilotSession session = client.createSession(config).get()) {
            var response = session
                    .sendAndWait(new MessageOptions().setPrompt("Run 'echo test'. If you can't, say 'failed'."))
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(response, "The session should complete after denying permission");
            assertEquals("failed", response.getData().content());
            assertEquals(1, permissionRequests.get(), "The failing permission handler must be invoked");
        }
    }

    /**
     * Verifies that the session continues to work after a tool error.
     *
     * @see Snapshot: tools/handles_tool_calling_errors
     */
}
