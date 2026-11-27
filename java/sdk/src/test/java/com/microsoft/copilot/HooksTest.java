/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.PostToolUseHookInput;
import com.microsoft.copilot.rpc.PreToolUseHookInput;
import com.microsoft.copilot.rpc.PreToolUseHookOutput;
import com.microsoft.copilot.rpc.SessionHooks;
import com.microsoft.copilot.rpc.UserPromptTransformedHookInput;
import com.microsoft.copilot.rpc.UserPromptTransformedHookOutput;

/**
 * Tests for hooks functionality (pre-tool-use and post-tool-use hooks).
 *
 * <p>
 * These tests use the shared CapiProxy infrastructure for deterministic API
 * response replay. Snapshots are stored in test/snapshots/hooks/.
 * </p>
 *
 * <p>
 * Note: Tests for userPromptSubmitted, sessionStart, and sessionEnd hooks are
 * not included as they are not tested in the reference implementation .NET or
 * Node.js SDKs and require test harness updates to properly invoke these hooks.
 * </p>
 */
@Tag("e2e")
public class HooksTest {

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
     * Verifies that both hooks are invoked for a single tool call.
     *
     * @see Snapshot: hooks/invoke_both_hooks_for_single_tool_call
     */
    @Test
    void testInvokeBothHooksForSingleToolCall() throws Exception {
        ctx.configureForTest("hooks", "invoke_both_hooks_for_single_tool_call");

        var preToolUseInputs = new CopyOnWriteArrayList<PreToolUseHookInput>();
        var postToolUseInputs = new CopyOnWriteArrayList<PostToolUseHookInput>();
        var invocationSessionIds = new CopyOnWriteArrayList<String>();

        var config = ctx.newSessionConfig()
                .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL))
                .setHooks(ctx.newSessionConfig().getHooks()
                        .setHandlers(new SessionHooks().setOnPreToolUse((input, invocation) -> {
                            preToolUseInputs.add(input);
                            invocationSessionIds.add(invocation.getSessionId());
                            return CompletableFuture.completedFuture(PreToolUseHookOutput.allow());
                        }).setOnPostToolUse((input, invocation) -> {
                            postToolUseInputs.add(input);
                            invocationSessionIds.add(invocation.getSessionId());
                            return CompletableFuture.completedFuture(null);
                        })));

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(config).get();

            // Create a file for the model to read
            Path testFile = ctx.getWorkDir().resolve("both.txt");
            Files.writeString(testFile, "Testing both hooks!");

            session.sendAndWait(new MessageOptions().setPrompt("Read the contents of both.txt")).get(60,
                    TimeUnit.SECONDS);

            // Both hooks should have been called
            assertFalse(preToolUseInputs.isEmpty(), "Should have received preToolUse hook calls");
            assertFalse(postToolUseInputs.isEmpty(), "Should have received postToolUse hook calls");
            assertTrue(invocationSessionIds.stream().allMatch(session.getSessionId()::equals),
                    "Hook invocations should carry the session ID");
            assertTrue(postToolUseInputs.stream().anyMatch(i -> i.getToolResult() != null),
                    "Should have received tool result in postToolUse hook");

            // The same tool should appear in both
            Set<String> preToolNames = preToolUseInputs.stream().map(PreToolUseHookInput::getToolName)
                    .filter(n -> n != null && !n.isEmpty()).collect(Collectors.toSet());
            Set<String> postToolNames = postToolUseInputs.stream().map(PostToolUseHookInput::getToolName)
                    .filter(n -> n != null && !n.isEmpty()).collect(Collectors.toSet());

            // Check if there's any overlap
            boolean hasOverlap = preToolNames.stream().anyMatch(postToolNames::contains);
            assertTrue(hasOverlap, "Expected the same tool to appear in both pre and post hooks");
        }
    }

    /**
     * Verifies that tool execution is denied when pre-tool-use returns deny.
     *
     * @see Snapshot: hooks/deny_tool_execution_when_pre_tool_use_returns_deny
     */
    @Test
    void testDenyToolExecutionWhenPreToolUseReturnsDeny() throws Exception {
        ctx.configureForTest("hooks", "deny_tool_execution_when_pre_tool_use_returns_deny");

        var preToolUseInputs = new CopyOnWriteArrayList<PreToolUseHookInput>();

        var config = ctx.newSessionConfig()
                .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL))
                .setHooks(ctx.newSessionConfig().getHooks()
                        .setHandlers(new SessionHooks().setOnPreToolUse((input, invocation) -> {
                            preToolUseInputs.add(input);
                            // Deny all tool calls
                            return CompletableFuture.completedFuture(PreToolUseHookOutput.deny());
                        })));

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(config).get();

            // Create a file
            Path testFile = ctx.getWorkDir().resolve("protected.txt");
            String originalContent = "Original content that should not be modified";
            Files.writeString(testFile, originalContent);

            var response = session
                    .sendAndWait(
                            new MessageOptions().setPrompt("Edit protected.txt and replace 'Original' with 'Modified'"))
                    .get(60, TimeUnit.SECONDS);

            // The hook should have been called
            assertFalse(preToolUseInputs.isEmpty(), "Should have received preToolUse hook calls");

            // The response should be defined
            assertNotNull(response, "Response should not be null");

            assertEquals(originalContent, Files.readString(testFile), "Denied preToolUse hook should block file edits");
        }
    }

    @Test
    void testInvokeUserPromptTransformedHookAndModifyTransformedPrompt() throws Exception {
        ctx.configureForTest("hooks_extended",
                "should_invoke_userprompttransformed_hook_and_modify_transformed_prompt");

        var inputs = new ArrayList<UserPromptTransformedHookInput>();
        var config = ctx.newSessionConfig()
                .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL))
                .setHooks(ctx.newSessionConfig().getHooks()
                        .setHandlers(new SessionHooks().setOnUserPromptTransformed((input, invocation) -> {
                            assertFalse(invocation.getSessionId().isBlank());
                            inputs.add(input);
                            return CompletableFuture.completedFuture(new UserPromptTransformedHookOutput(
                                    "Reply with exactly: HOOKED_TRANSFORMED_PROMPT"));
                        })));

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(config).get();
            var response = session.sendAndWait(new MessageOptions().setPrompt("Answer the request above.")).get(60,
                    TimeUnit.SECONDS);

            assertFalse(inputs.isEmpty());
            assertTrue(inputs.get(0).prompt().contains("Answer the request above."));
            assertTrue(inputs.get(0).transformedPrompt().contains("Answer the request above."));
            assertTrue(inputs.get(0).transformedPrompt().contains("<current_datetime>"));
            assertTrue(inputs.get(0).timestamp() > 0);
            assertFalse(inputs.get(0).cwd().isBlank());
            assertNotNull(response);
            assertTrue(response.getData().content().contains("HOOKED_TRANSFORMED_PROMPT"));
        }
    }
}
