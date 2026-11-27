/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.microsoft.copilot.generated.SessionEvent;
import com.microsoft.copilot.generated.AssistantMessageDeltaEvent;
import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.PermissionHandler;

/**
 * E2E tests for streaming fidelity — verifying that delta events are produced
 * when streaming is enabled and absent when it is disabled.
 *
 * <p>
 * Snapshots are stored in {@code test/snapshots/streaming_fidelity/}.
 * </p>
 */
@Tag("e2e")
public class StreamingFidelityTest {

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
     * Verifies that assistant.message_delta events are produced when streaming is
     * enabled.
     *
     * @see Snapshot:
     *      streaming_fidelity/should_produce_delta_events_when_streaming_is_enabled
     */
    @Test
    void testShouldProduceDeltaEventsWhenStreamingIsEnabled() throws Exception {
        ctx.configureForTest("streaming_fidelity", "should_produce_delta_events_when_streaming_is_enabled");

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(ctx.newSessionConfig()
                    .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL))
                    .setEvents(ctx.newSessionConfig().getEvents().setStreaming(true))).get();

            List<SessionEvent> events = new CopyOnWriteArrayList<>();
            session.on(events::add);

            session.sendAndWait(new MessageOptions().setPrompt("Count from 1 to 5, separated by commas.")).get(60,
                    TimeUnit.SECONDS);

            List<String> types = events.stream().map(SessionEvent::getType).toList();

            // Should have streaming deltas before the final message
            List<AssistantMessageDeltaEvent> deltaEvents = events.stream()
                    .filter(e -> e instanceof AssistantMessageDeltaEvent).map(e -> (AssistantMessageDeltaEvent) e)
                    .toList();
            assertFalse(deltaEvents.isEmpty(), "Should have received delta events when streaming is enabled");

            // Deltas should have content
            for (AssistantMessageDeltaEvent delta : deltaEvents) {
                assertFalse(delta.getData().deltaContent() == null || delta.getData().deltaContent().isEmpty(),
                        "Delta event should have content");
            }

            // Should still have a final assistant.message
            assertTrue(types.contains("assistant.message"), "Should have a final assistant.message event");

            // Deltas should come before the final message
            int firstDeltaIdx = types.indexOf("assistant.message_delta");
            int lastAssistantIdx = types.lastIndexOf("assistant.message");
            assertTrue(firstDeltaIdx < lastAssistantIdx, "Delta events should come before the final assistant.message");

            session.close();
        }
    }

    /**
     * Verifies that no delta events are produced when streaming is disabled.
     *
     * @see Snapshot:
     *      streaming_fidelity/should_not_produce_deltas_when_streaming_is_disabled
     */
    @Test
    void testShouldNotProduceDeltasWhenStreamingIsDisabled() throws Exception {
        ctx.configureForTest("streaming_fidelity", "should_not_produce_deltas_when_streaming_is_disabled");

        try (CopilotClient client = ctx.createClient()) {
            CopilotSession session = client.createSession(ctx.newSessionConfig()
                    .setPermissions(ctx.newSessionConfig().getPermissions().setOnRequest(PermissionHandler.APPROVE_ALL))
                    .setEvents(ctx.newSessionConfig().getEvents().setStreaming(false))).get();

            List<SessionEvent> events = new CopyOnWriteArrayList<>();
            session.on(events::add);

            session.sendAndWait(new MessageOptions().setPrompt("Say 'hello world'.")).get(60, TimeUnit.SECONDS);

            List<AssistantMessageDeltaEvent> deltaEvents = events.stream()
                    .filter(e -> e instanceof AssistantMessageDeltaEvent).map(e -> (AssistantMessageDeltaEvent) e)
                    .toList();

            // No deltas when streaming is off
            assertTrue(deltaEvents.isEmpty(), "Should not receive delta events when streaming is disabled");

            // But should still have a final assistant.message
            List<AssistantMessageEvent> assistantEvents = events.stream()
                    .filter(e -> e instanceof AssistantMessageEvent).map(e -> (AssistantMessageEvent) e).toList();
            assertFalse(assistantEvents.isEmpty(),
                    "Should still have a final assistant.message when streaming is disabled");

            session.close();
        }
    }
}
