/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import com.microsoft.copilot.rpc.SessionPermissionOptions;

import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.generated.ToolExecutionCompleteEvent;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.PermissionRequest;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.rpc.PermissionRequestResultKind;
import com.microsoft.copilot.rpc.ResumeSessionOptions;
import com.microsoft.copilot.rpc.MessageOptions;

/**
 * Tests for permission callback functionality.
 *
 * <p>
 * These tests use the shared CapiProxy infrastructure for deterministic API
 * response replay. Snapshots are stored in test/snapshots/permissions/.
 * </p>
 */
@Tag("e2e")
public class PermissionsTest {

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
     * Verifies that permission handler is invoked for write operations.
     *
     * @see Snapshot: permissions/permission_handler_for_write_operations
     */
    @Test
    void testPermissionHandlerForWriteOperations(TestInfo testInfo) throws Exception {
        ctx.configureForTest("permissions", "permission_handler_for_write_operations");

        var permissionRequests = new ArrayList<PermissionRequest>();

        final String[] sessionIdHolder = new String[1];

        var config = ctx.newSessionConfig()
                .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest((request, invocation) -> {
                    permissionRequests.add(request);
                    assertEquals(sessionIdHolder[0], invocation.getSessionId());
                    // Approve the permission
                    return CompletableFuture.completedFuture(
                            new PermissionRequestResult().setKind(PermissionRequestResultKind.APPROVED));
                }));

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(config).get();
            sessionIdHolder[0] = session.getSessionId();

            // Write a test file
            Path testFile = ctx.getWorkDir().resolve("test.txt");
            Files.writeString(testFile, "original content");

            session.sendAndWait(new MessageOptions().setPrompt("Edit test.txt and replace 'original' with 'modified'"))
                    .get(60, TimeUnit.SECONDS);

            // Should have received at least one permission request
            assertFalse(permissionRequests.isEmpty(), "Should have received permission requests");

            // Should include write permission request
            boolean hasWriteRequest = permissionRequests.stream().anyMatch(req -> "write".equals(req.getKind()));
            assertTrue(hasWriteRequest, "Should have received a write permission request");

            session.close();
        }
    }

    /**
     * Verifies that permissions can be denied.
     *
     * @see Snapshot: permissions/deny_permission
     */
    @Test
    void testDenyPermission(TestInfo testInfo) throws Exception {
        ctx.configureForTest("permissions", "deny_permission");

        var config = ctx.newSessionConfig()
                .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest((request, invocation) -> {
                    // Deny all permissions
                    return CompletableFuture.completedFuture(
                            new PermissionRequestResult().setKind(PermissionRequestResultKind.REJECTED));
                }));

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(config).get();

            String originalContent = "protected content";
            Path testFile = ctx.getWorkDir().resolve("protected.txt");
            Files.writeString(testFile, originalContent);

            session.sendAndWait(
                    new MessageOptions().setPrompt("Edit protected.txt and replace 'protected' with 'hacked'."))
                    .get(60, TimeUnit.SECONDS);

            // Verify the file was NOT modified
            String content = Files.readString(testFile);
            assertEquals(originalContent, content, "File should not have been modified");

            session.close();
        }
    }

    /**
     * Verifies that permission handler errors are handled gracefully.
     * <p>
     * When the handler throws an exception, the SDK should deny the permission and
     * the assistant should indicate it couldn't complete the task.
     * </p>
     *
     * @see Snapshot: permissions/should_handle_permission_handler_errors_gracefully
     */
    @Test
    void testShouldHandlePermissionHandlerErrorsGracefully(TestInfo testInfo) throws Exception {
        ctx.configureForTest("permissions", "should_handle_permission_handler_errors_gracefully");

        var config = ctx.newSessionConfig()
                .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest((request, invocation) -> {
                    // Throw an error in the handler
                    throw new RuntimeException("Handler error");
                }));

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(config).get();

            AssistantMessageEvent response = session
                    .sendAndWait(new MessageOptions().setPrompt("Run 'echo test'. If you can't, say 'failed'."))
                    .get(60, TimeUnit.SECONDS);

            // Should handle the error and deny permission
            assertNotNull(response);
            String content = response.getData().content().toLowerCase();
            assertTrue(content.contains("fail") || content.contains("cannot") || content.contains("unable")
                    || content.contains("permission"), "Response should indicate failure: " + content);

            session.close();
        }
    }

    /**
     * Verifies that tool operations are denied when the handler explicitly denies
     * after resuming a session.
     *
     * @see Snapshot:
     *      permissions/should_deny_tool_operations_when_handler_explicitly_denies_after_resume
     */
    @Test
    void testShouldDenyToolOperationsWhenHandlerExplicitlyDeniesAfterResume(TestInfo testInfo) throws Exception {
        ctx.configureForTest("permissions", "should_deny_tool_operations_when_handler_explicitly_denies_after_resume");

        try (CopilotClient client = ctx.createClient()) {
            var config = ctx.newSessionConfig().setPermissions(
                    ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL));
            CopilotSession session1 = client.createSession(config).get();
            String sessionId = session1.getSessionId();
            session1.sendAndWait(new MessageOptions().setPrompt("What is 1+1?")).get(60, TimeUnit.SECONDS);
            session1.close();

            CopilotSession session2 = client.resumeSession(sessionId,
                    new ResumeSessionOptions().setPermissions(new SessionPermissionOptions().setOnRequest(
                            (request, invocation) -> CompletableFuture.completedFuture(new PermissionRequestResult()
                                    .setKind(PermissionRequestResultKind.USER_NOT_AVAILABLE)))))
                    .get();

            final boolean[] permissionDenied = {false};
            session2.on(ToolExecutionCompleteEvent.class, evt -> {
                if (!evt.getData().success() && evt.getData().error() != null && evt.getData().error().message() != null
                        && evt.getData().error().message().contains("Permission denied")) {
                    permissionDenied[0] = true;
                }
            });

            session2.sendAndWait(new MessageOptions().setPrompt("Run 'node --version'")).get(60, TimeUnit.SECONDS);

            assertTrue(permissionDenied[0], "Expected a tool.execution_complete event with Permission denied result");

            session2.close();
        }
    }

    /**
     * Verifies that the SDK correctly waits for a slow permission handler before
     * completing tool execution.
     *
     * @see Snapshot: permissions/should_wait_for_slow_permission_handler
     */
    @Test
    void testShouldWaitForSlowPermissionHandler() throws Exception {
        ctx.configureForTest("permissions", "should_wait_for_slow_permission_handler");

        var handlerEntered = new CompletableFuture<Void>();
        var releaseHandler = new CompletableFuture<Void>();
        var toolCallId = new CompletableFuture<String>();

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(ctx.newSessionConfig()
                    .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest((request, invocation) -> {
                        toolCallId.complete(request.getToolCallId());
                        handlerEntered.complete(null);
                        return releaseHandler.thenApply(
                                v -> new PermissionRequestResult().setKind(PermissionRequestResultKind.APPROVED));
                    }))).get();

            // Capture the sendAndWait future before awaiting it so we can interact with the
            // handler
            CompletableFuture<AssistantMessageEvent> responseFuture = session
                    .sendAndWait(new MessageOptions().setPrompt("Run 'echo slow_handler_test'"), 0);

            // Wait for permission handler to be entered
            try {
                handlerEntered.get(30, TimeUnit.SECONDS);
            } finally {
                releaseHandler.complete(null);
            }

            // Session should complete successfully
            AssistantMessageEvent message = responseFuture.get(60, TimeUnit.SECONDS);
            assertNotNull(message);
            assertNotNull(toolCallId.getNow(null), "Should have received toolCallId in permission request");
            assertFalse(toolCallId.getNow(null).isEmpty(), "Tool call ID should not be empty");

            session.close();
        }
    }
}
