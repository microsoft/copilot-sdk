/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.PermissionHandler;

/**
 * Tests for closed-session guard functionality in CopilotSession.
 *
 * <p>
 * Verifies that all public methods that interact with session state throw
 * IllegalStateException when invoked after close(), that identity accessors
 * remain available, and that close() itself is idempotent.
 * </p>
 */
@Tag("e2e")
public class ClosedSessionGuardTest {

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

    @Test
    void closedSessionRejectsOperationsAndKeepsIdentity() throws Exception {
        ctx.configureForTest("session", "should_receive_session_events");
        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(ctx.newSessionConfig().setPermissions(
                    ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL))).get();
            String sessionIdBeforeClose = session.getSessionId();
            String pathBeforeClose = session.getWorkspacePath();

            try (session) {
                session.close();
            } // Second close happens here
            assertDoesNotThrow(() -> session.close());

            Map<String, Executable> operations = Map.ofEntries(Map.entry("send(String)", () -> session.send("test")),
                    Map.entry("send(MessageOptions)", () -> session.send(new MessageOptions().setPrompt("test"))),
                    Map.entry("sendAndWait(String)", () -> session.sendAndWait("test")),
                    Map.entry("sendAndWait(MessageOptions)",
                            () -> session.sendAndWait(new MessageOptions().setPrompt("test"))),
                    Map.entry("sendAndWait(MessageOptions, long)",
                            () -> session.sendAndWait(new MessageOptions().setPrompt("test"), 5000)),
                    Map.entry("on(Consumer)", () -> session.on(evt -> {
                    })), Map.entry("on(Class, Consumer)", () -> session.on(AssistantMessageEvent.class, msg -> {
                    })), Map.entry("getMessages", () -> session.getMessages()),
                    Map.entry("abort", () -> session.abort()),
                    Map.entry("setEventErrorHandler", () -> session.setEventErrorHandler((event, ex) -> {
                    })),
                    Map.entry("setEventErrorPolicy",
                            () -> session.setEventErrorPolicy(EventErrorPolicy.SUPPRESS_AND_LOG_ERRORS)),
                    Map.entry("setModel(String)", () -> session.setModel("gpt-4.1")));
            operations.forEach((name, operation) -> {
                IllegalStateException thrown = assertThrows(IllegalStateException.class, operation, name);
                assertTrue(thrown.getMessage().contains("closed"), name + " should mention session is closed");
            });

            assertEquals(sessionIdBeforeClose, session.getSessionId(),
                    "Session ID should remain accessible after close");
            assertEquals(pathBeforeClose, session.getWorkspacePath(),
                    "Workspace path should remain accessible after close");
        }
    }
}
