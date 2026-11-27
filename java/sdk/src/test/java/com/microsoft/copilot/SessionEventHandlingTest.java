/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.microsoft.copilot.generated.SessionEvent;
import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.generated.ExternalToolCompletedEvent;
import com.microsoft.copilot.generated.ExternalToolRequestedEvent;
import com.microsoft.copilot.generated.SessionIdleEvent;
import com.microsoft.copilot.generated.SessionErrorEvent;
import com.microsoft.copilot.generated.SessionMode;
import com.microsoft.copilot.generated.SessionStartEvent;
import com.microsoft.copilot.generated.rpc.SessionToolsGetCurrentMetadataResult;
import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.SendMessageResponse;
import com.microsoft.copilot.rpc.ToolDefinition;

/**
 * Unit tests for session event handling API.
 * <p>
 * These are pure unit tests that don't require the Copilot CLI. They test the
 * event dispatch mechanism directly.
 */
public class SessionEventHandlingTest {

    private CopilotSession session;

    @Test
    void externalToolRequestBuildersPreserveNullablePresence() {
        var omitted = ExternalToolRequestedEvent.ExternalToolRequestedEventData.builder().requestId("request")
                .sessionId("session").toolCallId("call").toolName("tool").providerId("provider").build();
        var explicit = ExternalToolRequestedEvent.ExternalToolRequestedEventData.builder().requestId("request")
                .sessionId("session").toolCallId("call").toolName("tool").providerId(null).build();
        assertEquals("provider", omitted.providerId());
        assertNull(omitted.hasHandler());
        assertTrue(explicit.$nullableProperties().containsKey("providerId"));
        assertNull(explicit.hasHandler());
    }

    @BeforeEach
    void setup() throws Exception {
        // Create a minimal session for testing event handling
        // We use reflection to create a session without a real RPC connection
        session = createTestSession();
    }

    private CopilotSession createTestSession() throws Exception {
        return createTestSession(null);
    }

    private CopilotSession createTestSession(JsonRpcClient rpc) throws Exception {
        if (rpc != null) {
            when(rpc.invoke(eq("session.sendAndWait"), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(CompletableFuture.failedFuture(new JsonRpcException(-32601, "Method not found")));
        }
        // Use the package-private constructor via reflection for testing
        var constructor = CopilotSession.class.getDeclaredConstructor(String.class, JsonRpcClient.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("test-session-id", rpc, null);
    }

    @Test
    void testHandlerAddedDuringDispatchStartsWithNextEvent() {
        var received = new ArrayList<SessionEvent>();
        // Fixed hashes make the old weakly consistent iterator visit the new handler
        // after the registering handlers, without relying on scheduling or identity
        // hashes.
        record Handler(int hash, Consumer<SessionEvent> callback) implements Consumer<SessionEvent> {
            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public void accept(SessionEvent event) {
                callback.accept(event);
            }
        }
        var added = new Handler(15, received::add);
        session.on(new Handler(0, event -> session.on(added)));
        session.on(new Handler(1, event -> session.on(added)));

        dispatchEvent(createSessionIdleEvent());
        assertTrue(received.isEmpty(), "A new wait must not receive the idle event already being dispatched");

        var next = createAssistantMessageEvent("HELLO");
        dispatchEvent(next);
        assertEquals(List.of(next), received);
    }

    @Test
    void testExternalToolCompletedCancelsBlockedHandler() throws Exception {
        var toolFuture = new CompletableFuture<Object>();
        var started = new CountDownLatch(1);
        session.registerTools(List.of(ToolDefinition.create("blocked_tool", "Blocks", Map.of(), invocation -> {
            started.countDown();
            return toolFuture;
        })));

        var requested = new ExternalToolRequestedEvent();
        requested.setData(ExternalToolRequestedEvent.ExternalToolRequestedEventData.builder().requestId("request-1")
                .sessionId("test-session-id").toolCallId("tool-call-1").toolName("blocked_tool").providerId(null)
                .arguments(Map.of()).workingDirectory(null).traceparent(null).tracestate(null).build());
        dispatchEvent(requested);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        var completed = new ExternalToolCompletedEvent();
        completed.setData(
                ExternalToolCompletedEvent.ExternalToolCompletedEventData.builder().requestId("request-1").build());
        dispatchEvent(completed);

        assertThrows(CancellationException.class, () -> toolFuture.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testCloseCancelsBlockedExternalTool() throws Exception {
        var rpc = mock(JsonRpcClient.class);
        when(rpc.invoke(eq("session.detach"), any(), eq(CopilotSession.SessionDetachResponse.class)))
                .thenReturn(CompletableFuture.completedFuture(new CopilotSession.SessionDetachResponse(true, null)));
        session = createTestSession(rpc);
        var toolFuture = new CompletableFuture<Object>();
        var started = new CountDownLatch(1);
        session.registerTools(List.of(ToolDefinition.create("blocked_tool", "Blocks", Map.of(), invocation -> {
            started.countDown();
            return toolFuture;
        })));

        var requested = new ExternalToolRequestedEvent();
        requested.setData(ExternalToolRequestedEvent.ExternalToolRequestedEventData.builder().requestId("request-close")
                .sessionId("test-session-id").toolCallId("tool-call-close").toolName("blocked_tool").providerId(null)
                .arguments(Map.of()).workingDirectory(null).traceparent(null).tracestate(null).build());
        dispatchEvent(requested);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        session.close();

        assertThrows(CancellationException.class, () -> toolFuture.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testExternalToolCompletedDoesNotBlockOnSynchronousHandler() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        session.registerTools(
                List.of(ToolDefinition.create("blocked_tool", "Blocks synchronously", Map.of(), invocation -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return CompletableFuture.completedFuture("done");
                })));

        var requested = new ExternalToolRequestedEvent();
        requested.setData(ExternalToolRequestedEvent.ExternalToolRequestedEventData.builder().requestId("request-sync")
                .sessionId("test-session-id").toolCallId("tool-call-sync").toolName("blocked_tool").providerId(null)
                .arguments(Map.of()).workingDirectory(null).traceparent(null).tracestate(null).build());
        dispatchEvent(requested);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        var completed = new ExternalToolCompletedEvent();
        completed.setData(
                ExternalToolCompletedEvent.ExternalToolCompletedEventData.builder().requestId("request-sync").build());
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> dispatchEvent(completed));
        } finally {
            release.countDown();
        }
    }

    @Test
    void testToolSearchRunsWhenMetadataResultIsNull() throws Exception {
        var rpc = mock(JsonRpcClient.class);
        CompletableFuture<SessionToolsGetCurrentMetadataResult> metadata = CompletableFuture.completedFuture(null);
        when(rpc.invoke(eq("session.tools.getCurrentMetadata"), any(), eq(SessionToolsGetCurrentMetadataResult.class)))
                .thenReturn(metadata);
        session = createTestSession(rpc);
        var invoked = new CountDownLatch(1);
        session.registerTools(List.of(ToolDefinition.create("tool_search_tool", "Searches", Map.of(), invocation -> {
            invoked.countDown();
            return new CompletableFuture<>();
        })));

        var requested = new ExternalToolRequestedEvent();
        requested.setData(ExternalToolRequestedEvent.ExternalToolRequestedEventData.builder()
                .requestId("request-search").sessionId("test-session-id").toolCallId("tool-call-search")
                .toolName("tool_search_tool").providerId(null).arguments(Map.of()).workingDirectory(null)
                .traceparent(null).tracestate(null).build());
        dispatchEvent(requested);

        assertTrue(invoked.await(1, TimeUnit.SECONDS));
    }

    @Test
    void testTypedEventHandler() {
        var receivedMessages = new ArrayList<AssistantMessageEvent>();

        session.on(AssistantMessageEvent.class, msg -> receivedMessages.add(msg));

        // Dispatch various events - only AssistantMessageEvent should be captured
        dispatchEvent(createSessionStartEvent());
        dispatchEvent(createAssistantMessageEvent("First message"));
        dispatchEvent(createSessionIdleEvent());
        dispatchEvent(createAssistantMessageEvent("Second message"));

        // Should only have the two assistant messages
        assertEquals(2, receivedMessages.size());
        assertEquals("First message", receivedMessages.get(0).getData().content());
        assertEquals("Second message", receivedMessages.get(1).getData().content());
    }

    @Test
    void testSendReturnsAfterRpcAcceptanceWithoutWaitingForIdle() throws Exception {
        var rpc = mock(JsonRpcClient.class);
        when(rpc.invoke(eq("session.send"), any(), eq(SendMessageResponse.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendMessageResponse("message-1")));
        session = createTestSession(rpc);
        var events = new ArrayList<SessionEvent>();
        session.on(events::add);

        session.send("hello").get(1, TimeUnit.SECONDS);

        verify(rpc).invoke(eq("session.send"), any(), eq(SendMessageResponse.class));
        assertTrue(events.isEmpty());
        dispatchEvent(createAssistantMessageEvent("done"));
        dispatchEvent(createSessionIdleEvent());
        assertEquals(List.of("assistant.message", "session.idle"), events.stream().map(SessionEvent::getType).toList());
    }

    @Test
    void testSendAndWaitSkipsAutopilotContinuationIdle() throws Exception {
        var rpc = mock(JsonRpcClient.class);
        when(rpc.invoke(eq("session.send"), any(), eq(SendMessageResponse.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendMessageResponse("message-1")));
        when(rpc.invoke(eq("session.detach"), any(), eq(CopilotSession.SessionDetachResponse.class)))
                .thenReturn(CompletableFuture.completedFuture(new CopilotSession.SessionDetachResponse(true, null)));
        session = createTestSession(rpc);

        try {
            var pending = session.sendAndWait(new MessageOptions().setPrompt("keep going"), 0);

            dispatchEvent(createUserMessageEvent("keep going"));
            dispatchEvent(createAssistantMessageEvent("intermediate"));
            dispatchEvent(createSessionIdleEvent(SessionMode.AUTOPILOT));
            assertFalse(pending.isDone(), "Autopilot continuation idle must not complete sendAndWait");

            dispatchEvent(createAssistantMessageEvent("final"));
            dispatchEvent(createSessionIdleEvent(SessionMode.INTERACTIVE));

            var result = pending.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals("final", result.getData().content());
        } finally {
            session.close();
        }
    }

    @Test
    void testSendAndWaitIgnoresChildEvents() throws Exception {
        var rpc = mock(JsonRpcClient.class);
        when(rpc.invoke(eq("session.send"), any(), eq(SendMessageResponse.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendMessageResponse("message-1")));
        when(rpc.invoke(eq("session.detach"), any(), eq(CopilotSession.SessionDetachResponse.class)))
                .thenReturn(CompletableFuture.completedFuture(new CopilotSession.SessionDetachResponse(true, null)));
        session = createTestSession(rpc);

        try {
            var received = new ArrayList<SessionEvent>();
            session.on(received::add);
            var pending = session.sendAndWait(new MessageOptions().setPrompt("delegate"), 0);

            var childMessage = createAssistantMessageEvent("child reply");
            childMessage.setAgentId("child-1");
            dispatchEvent(childMessage);
            var childError = new SessionErrorEvent();
            childError.setAgentId("child-1");
            childError.setData(SessionErrorEvent.SessionErrorEventData.builder().originatingMessageId("message-1")
                    .errorType("query").errorCode(null).providerErrorType(null).providerErrorParam(null)
                    .eligibleForAutoSwitch(null).message("child failed").remediation(null).stack(null).statusCode(null)
                    .providerCallId(null).serviceRequestId(null).url(null).build());
            dispatchEvent(childError);
            var childIdle = createSessionIdleEvent();
            childIdle.setAgentId("child-1");
            dispatchEvent(childIdle);
            assertEquals(List.of(childMessage, childError, childIdle), received);
            assertFalse(pending.isDone(), "Child events must not complete the parent wait");

            dispatchEvent(createUserMessageEvent("delegate"));
            dispatchEvent(createSessionIdleEvent());
            assertNull(pending.get(5, TimeUnit.SECONDS), "Child reply must not supply the parent result");
        } finally {
            session.close();
        }
    }

    @Test
    void testUnsubscribeGenericHandler() {
        var count = new AtomicInteger(0);

        Closeable subscription = session.on(event -> count.incrementAndGet());

        dispatchEvent(createSessionStartEvent());
        assertEquals(1, count.get());

        try {
            subscription.close();
        } catch (Exception e) {
            fail("Unsubscribe should not throw: " + e.getMessage());
        }

        dispatchEvent(createSessionIdleEvent());
        assertEquals(1, count.get()); // Still 1
    }

    @Test
    void testMixedHandlers() {
        var allEvents = new ArrayList<String>();
        var messageEvents = new ArrayList<String>();

        // Generic handler captures everything
        session.on(event -> allEvents.add(event.getType()));

        // Typed handler captures only messages
        session.on(AssistantMessageEvent.class, msg -> messageEvents.add(msg.getData().content()));

        dispatchEvent(createSessionStartEvent());
        dispatchEvent(createAssistantMessageEvent("Hello"));
        dispatchEvent(createSessionIdleEvent());

        assertEquals(3, allEvents.size());
        assertEquals(1, messageEvents.size());
        assertEquals("Hello", messageEvents.get(0));
    }

    @Test
    void testUnsubscribeOneKeepsOther() {
        var count1 = new AtomicInteger();
        var count2 = new AtomicInteger();

        var sub1 = session.on(AssistantMessageEvent.class, msg -> count1.incrementAndGet());
        session.on(AssistantMessageEvent.class, msg -> count2.incrementAndGet());

        dispatchEvent(createAssistantMessageEvent("before"));
        assertEquals(1, count1.get());
        assertEquals(1, count2.get());

        // Unsubscribe first handler
        try {
            sub1.close();
        } catch (Exception e) {
            fail("Unsubscribe should not throw: " + e.getMessage());
        }

        dispatchEvent(createAssistantMessageEvent("after"));
        assertEquals(1, count1.get(), "Unsubscribed handler should not be called again");
        assertEquals(2, count2.get(), "Remaining handler should still be called");
    }

    @Test
    void testAllHandlersInvoked() {
        var called = new ArrayList<String>();

        session.on(AssistantMessageEvent.class, msg -> called.add("first"));
        session.on(AssistantMessageEvent.class, msg -> called.add("second"));
        session.on(AssistantMessageEvent.class, msg -> called.add("third"));

        dispatchEvent(createAssistantMessageEvent("test"));

        assertEquals(3, called.size(), "All three handlers should be invoked");
        assertTrue(called.containsAll(List.of("first", "second", "third")), "All handler labels should be present");
    }

    // Helper methods to dispatch events using reflection
    // ====================================================================
    // EventErrorHandler tests
    // ====================================================================

    @Test
    void testDefaultPolicyPropagatesAndLogs() {
        // Default policy is PROPAGATE_AND_LOG_ERRORS — stops dispatch on first error
        var handler1Called = new AtomicInteger(0);
        var handler2Called = new AtomicInteger(0);

        Logger sessionLogger = Logger.getLogger(CopilotSession.class.getName());
        Level originalLevel = sessionLogger.getLevel();
        sessionLogger.setLevel(Level.OFF);

        try {
            // Both handlers throw — with PROPAGATE only one should execute
            session.on(AssistantMessageEvent.class, msg -> {
                handler1Called.incrementAndGet();
                throw new RuntimeException("boom 1");
            });

            session.on(AssistantMessageEvent.class, msg -> {
                handler2Called.incrementAndGet();
                throw new RuntimeException("boom 2");
            });

            assertDoesNotThrow(() -> dispatchEvent(createAssistantMessageEvent("Test")));

            // Only one handler should execute (default PROPAGATE_AND_LOG_ERRORS policy)
            int totalCalls = handler1Called.get() + handler2Called.get();
            assertEquals(1, totalCalls, "Only one handler should execute with default PROPAGATE_AND_LOG_ERRORS policy");
        } finally {
            sessionLogger.setLevel(originalLevel);
        }
    }

    @Test
    void testCustomEventErrorHandlerReceivesEventAndException() {
        var capturedEvents = new ArrayList<SessionEvent>();
        var capturedExceptions = new ArrayList<Exception>();

        Logger sessionLogger = Logger.getLogger(CopilotSession.class.getName());
        Level originalLevel = sessionLogger.getLevel();
        sessionLogger.setLevel(Level.OFF);

        try {
            session.setEventErrorHandler((event, exception) -> {
                capturedEvents.add(event);
                capturedExceptions.add(exception);
            });

            var thrownException = new RuntimeException("test error");
            session.on(AssistantMessageEvent.class, msg -> {
                throw thrownException;
            });

            var event = createAssistantMessageEvent("Hello");
            dispatchEvent(event);

            assertEquals(1, capturedEvents.size());
            assertSame(event, capturedEvents.get(0));
            assertEquals(1, capturedExceptions.size());
            assertSame(thrownException, capturedExceptions.get(0));
        } finally {
            sessionLogger.setLevel(originalLevel);
        }
    }

    @Test
    void testSetEventErrorHandlerToNullRestoresDefaultBehavior() {
        var errorCount = new AtomicInteger(0);

        Logger sessionLogger = Logger.getLogger(CopilotSession.class.getName());
        Level originalLevel = sessionLogger.getLevel();
        sessionLogger.setLevel(Level.OFF);

        try {
            // Set custom handler
            session.setEventErrorHandler((event, exception) -> {
                errorCount.incrementAndGet();
            });

            session.on(AssistantMessageEvent.class, msg -> {
                throw new RuntimeException("error");
            });

            dispatchEvent(createAssistantMessageEvent("Test1"));
            assertEquals(1, errorCount.get());

            // Reset to null (restore default logging-only behavior)
            session.setEventErrorHandler(null);

            dispatchEvent(createAssistantMessageEvent("Test2"));

            // Custom handler should NOT have been called again
            assertEquals(1, errorCount.get());
        } finally {
            sessionLogger.setLevel(originalLevel);
        }
    }

    // ====================================================================
    // EventErrorPolicy tests
    // ====================================================================

    @Test
    void testPropagatePolicyStopsOnFirstError() {
        var handler1Called = new AtomicInteger(0);
        var handler2Called = new AtomicInteger(0);
        var errorHandlerCalls = new AtomicInteger(0);

        Logger sessionLogger = Logger.getLogger(CopilotSession.class.getName());
        Level originalLevel = sessionLogger.getLevel();
        sessionLogger.setLevel(Level.OFF);

        try {
            session.setEventErrorPolicy(EventErrorPolicy.PROPAGATE_AND_LOG_ERRORS);
            session.setEventErrorHandler((event, exception) -> {
                errorHandlerCalls.incrementAndGet();
            });

            // Two handlers that throw
            session.on(AssistantMessageEvent.class, msg -> {
                handler1Called.incrementAndGet();
                throw new RuntimeException("error 1");
            });

            session.on(AssistantMessageEvent.class, msg -> {
                handler2Called.incrementAndGet();
                throw new RuntimeException("error 2");
            });

            dispatchEvent(createAssistantMessageEvent("Test"));

            // Only one handler should have been called (PROPAGATE_AND_LOG_ERRORS policy)
            assertEquals(1, errorHandlerCalls.get());
            int totalCalls = handler1Called.get() + handler2Called.get();
            assertEquals(1, totalCalls, "Only one handler should execute with PROPAGATE_AND_LOG_ERRORS policy");
        } finally {
            sessionLogger.setLevel(originalLevel);
        }
    }

    @Test
    void testSuppressPolicyWithMultipleErrors() {
        var errorHandlerCalls = new AtomicInteger(0);
        var successfulHandlerCalls = new AtomicInteger(0);

        Logger sessionLogger = Logger.getLogger(CopilotSession.class.getName());
        Level originalLevel = sessionLogger.getLevel();
        sessionLogger.setLevel(Level.OFF);

        try {
            session.setEventErrorPolicy(EventErrorPolicy.SUPPRESS_AND_LOG_ERRORS);
            session.setEventErrorHandler((event, exception) -> {
                errorHandlerCalls.incrementAndGet();
            });

            session.on(AssistantMessageEvent.class, msg -> {
                throw new RuntimeException("error 1");
            });
            session.on(AssistantMessageEvent.class, msg -> {
                throw new RuntimeException("error 2");
            });
            session.on(AssistantMessageEvent.class, msg -> {
                successfulHandlerCalls.incrementAndGet();
            });
            session.on(AssistantMessageEvent.class, msg -> {
                throw new RuntimeException("error 3");
            });

            dispatchEvent(createAssistantMessageEvent("Test"));

            // All errors should be reported, successful handler should run
            assertEquals(3, errorHandlerCalls.get());
            assertEquals(1, successfulHandlerCalls.get());
        } finally {
            sessionLogger.setLevel(originalLevel);
        }
    }

    @Test
    void testErrorHandlerThrowingStopsRegardlessOfPolicy() {
        var handler1Called = new AtomicInteger(0);
        var handler2Called = new AtomicInteger(0);

        Logger sessionLogger = Logger.getLogger(CopilotSession.class.getName());
        Level originalLevel = sessionLogger.getLevel();
        sessionLogger.setLevel(Level.OFF);

        try {
            // SUPPRESS_AND_LOG_ERRORS policy, but error handler throws
            session.setEventErrorPolicy(EventErrorPolicy.SUPPRESS_AND_LOG_ERRORS);
            session.setEventErrorHandler((event, exception) -> {
                throw new RuntimeException("error handler broke");
            });

            session.on(AssistantMessageEvent.class, msg -> {
                handler1Called.incrementAndGet();
                throw new RuntimeException("error");
            });

            session.on(AssistantMessageEvent.class, msg -> {
                handler2Called.incrementAndGet();
                throw new RuntimeException("error");
            });

            assertDoesNotThrow(() -> dispatchEvent(createAssistantMessageEvent("Test")));

            // Error handler threw — should stop regardless of SUPPRESS_AND_LOG_ERRORS
            // policy
            int totalCalls = handler1Called.get() + handler2Called.get();
            assertEquals(1, totalCalls,
                    "Only one handler should execute when error handler throws, even with SUPPRESS_AND_LOG_ERRORS policy");
        } finally {
            sessionLogger.setLevel(originalLevel);
        }
    }

    // ====================================================================
    // Helper methods
    // ====================================================================

    private void dispatchEvent(SessionEvent event) {
        try {
            Method dispatchMethod = CopilotSession.class.getDeclaredMethod("dispatchEvent", SessionEvent.class);
            dispatchMethod.setAccessible(true);
            dispatchMethod.invoke(session, event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to dispatch event", e);
        }
    }

    // Factory methods for creating test events
    private SessionStartEvent createSessionStartEvent() {
        return createSessionStartEvent("test-session");
    }

    private SessionStartEvent createSessionStartEvent(String sessionId) {
        var event = new SessionStartEvent();
        var data = SessionStartEvent.SessionStartEventData.builder().sessionId(sessionId).version(1L).producer("test")
                .copilotVersion("test").startTime(OffsetDateTime.parse("2026-01-01T00:00:00Z")).selectedModel(null)
                .reasoningEffort(null).reasoningEffortModel(null).reasoningSummary(null).verbosity(null)
                .contextTier(null).autoTier(null).sessionLimits(null).context(null).gitHubMcpToolConfig(null)
                .alreadyInUse(null).remoteSteerable(null).detachedFromSpawningParentSessionId(null).build();
        event.setData(data);
        return event;
    }

    private SessionEvent createUserMessageEvent(String content) {
        return JsonRpcClient.getObjectMapper().convertValue(
                Map.of("type", "user.message", "data", Map.of("messageId", "message-1", "content", content)),
                SessionEvent.class);
    }

    private AssistantMessageEvent createAssistantMessageEvent(String content) {
        var event = new AssistantMessageEvent();
        var data = AssistantMessageEvent.AssistantMessageEventData.builder().messageId("assistant-message")
                .originatingMessageId(null).model(null).providerId(null).content(content).toolRequests(null)
                .reasoningOpaque(null).reasoningText(null).reasoningWireField(null).encryptedContent(null).phase(null)
                .chunkIndex(null).chunkCount(null).outputTokens(null).interactionId(null).requestId(null)
                .clientRequestId(null).serviceRequestId(null).rte(null).apiCallId(null).serverTools(null)
                .reasoningBlocks(null).turnId(null).citations(null).fusion(null).build();
        event.setData(data);
        return event;
    }

    private SessionIdleEvent createSessionIdleEvent() {
        return new SessionIdleEvent();
    }

    private SessionIdleEvent createSessionIdleEvent(SessionMode mode) {
        var event = new SessionIdleEvent();
        event.setData(SessionIdleEvent.SessionIdleEventData.builder().aborted(null).mode(mode).build());
        return event;
    }
}
