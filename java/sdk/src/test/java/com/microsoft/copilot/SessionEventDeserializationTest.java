/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.microsoft.copilot.generated.*;
import com.microsoft.copilot.generated.rpc.SessionMcpIdeStateResult;

/**
 * Tests for session event deserialization.
 * <p>
 * These are unit tests that verify JSON deserialization works correctly for all
 * event types supported by the SDK.
 * </p>
 */
public class SessionEventDeserializationTest {

    private static final ObjectMapper MAPPER = JsonRpcClient.getObjectMapper();

    /**
     * Helper to parse a JSON string directly to a {@link SessionEvent}.
     */
    private static SessionEvent parseJson(String json) throws Exception {
        return MAPPER.readValue(json, SessionEvent.class);
    }

    private static String eventEnvelope(String type, String payload) {
        return """
                {
                    "id": "00000000-0000-4000-8000-000000000001",
                    "timestamp": "2026-09-18T12:00:00Z",
                    "parentId": "00000000-0000-4000-8000-000000000002",
                    "agentId": "test-agent",
                    "ephemeral": true,
                    "type": "%s",
                    "data": %s
                }
                """.formatted(type, payload);
    }

    @Test
    void testSandboxStatePreservesRequiredNulls() throws Exception {
        var wire = eventEnvelope("session.sandbox_state_changed", """
                {"incarnation":1,"revision":2,"config":null,"configSource":"settings",
                 "disabledForSession":false,"autoApproveBypass":false,"sessionOverride":null}
                """);
        var event = assertInstanceOf(SessionSandboxStateChangedEvent.class, parseJson(wire));
        assertEquals(MAPPER.readTree(wire), MAPPER.readTree(MAPPER.writeValueAsString(event)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"newModel\":\"auto\"}", "{\"newModel\":\"auto\",\"contextTier\":null}",
            "{\"newModel\":\"auto\",\"contextTier\":\"long_context\"}"})
    void testModelChangeContextTierPresence(String payload) throws Exception {
        var expected = MAPPER.readTree(payload);
        var builder = SessionModelChangeEvent.SessionModelChangeEventData.builder().newModel("auto");
        if (expected.has("contextTier")) {
            builder.contextTier(expected.get("contextTier").isNull()
                    ? null
                    : ContextTier.fromValue(expected.get("contextTier").asText()));
        }
        var built = builder.build();
        assertEquals(expected, MAPPER.valueToTree(built));

        try (var parser = new com.fasterxml.jackson.core.JsonFactory().createParser(payload)) {
            assertEquals(built, MAPPER.readValue(parser, SessionModelChangeEvent.SessionModelChangeEventData.class));
        }
        var envelope = eventEnvelope("session.model_change", payload);
        var event = assertInstanceOf(SessionModelChangeEvent.class, parseJson(envelope));
        assertEquals(built, event.getData());
        assertEquals(MAPPER.readTree(envelope), MAPPER.valueToTree(event));
    }

    @ParameterizedTest
    @ValueSource(strings = {"session.info", "future.event"})
    void testCheckpointMarkerRoundTrip(String type) throws Exception {
        for (Boolean checkpoint : new Boolean[]{null, false, true}) {
            var wire = MAPPER.readValue(eventEnvelope(type, """
                    {"infoType":"status","message":"Checkpoint state"}
                    """), ObjectNode.class);
            if (checkpoint != null) {
                wire.put("checkpoint", checkpoint);
            }
            var event = parseJson(wire.toString());
            assertEquals(checkpoint, event.getCheckpoint());
            if ("future.event".equals(type)) {
                assertInstanceOf(UnknownSessionEvent.class, event);
            } else {
                assertInstanceOf(SessionInfoEvent.class, event);
            }
            assertEquals(wire, MAPPER.valueToTree(event));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"session.info", "future.event"})
    void testOccurredAtRoundTrip(String type) throws Exception {
        for (String occurredAt : new String[]{null, "2026-09-18T11:59:30Z"}) {
            var wire = MAPPER.readValue(eventEnvelope(type, """
                    {"infoType":"status","message":"Forwarded state"}
                    """), ObjectNode.class);
            if (occurredAt != null) {
                wire.put("occurredAt", occurredAt);
            }
            var event = parseJson(wire.toString());
            assertEquals(occurredAt == null ? null : OffsetDateTime.parse(occurredAt), event.getOccurredAt());
            assertEquals(wire, MAPPER.valueToTree(event));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testIdeSelectionRoundTrip(boolean cleared) throws Exception {
        String selection = cleared ? "null" : """
                {
                    "text": "selected",
                    "filePath": "/workspace/file.rs",
                    "fileUrl": "file:///workspace/file.rs",
                    "selection": {
                        "start": {"line": 0.0, "character": 0.0},
                        "end": {"line": 0.0, "character": 8.0},
                        "isEmpty": false
                    }
                }
                """;
        var wire = MAPPER.readValue(eventEnvelope("ide.selection_changed", "{\"selection\": %s}".formatted(selection)),
                ObjectNode.class);

        var event = assertInstanceOf(IdeSelectionChangedEvent.class, parseJson(wire.toString()));

        if (cleared) {
            assertNull(event.getData().selection());
        } else {
            assertEquals("selected", event.getData().selection().text());
        }
        assertEquals(wire, MAPPER.valueToTree(event));
    }

    @Test
    void testIdeRpcStateSharesCanonicalEventModels() throws Exception {
        String selectionJson = """
                {
                    "text": "selected",
                    "filePath": "/workspace/file.rs",
                    "fileUrl": "file:///workspace/file.rs",
                    "selection": {
                        "start": {"line": 2.0, "character": 3.0},
                        "end": {"line": 4.0, "character": 5.0},
                        "isEmpty": false
                    },
                    "current": true
                }
                """;
        String connectionJson = """
                {
                    "ideName": "VS Code",
                    "workspaceFolder": "/workspace",
                    "pid": 42,
                    "lockFilePath": "/workspace/ide.lock",
                    "serverName": "ide"
                }
                """;
        String stateJson = """
                {"connectedIde": %s, "retainedIde": %s, "selection": %s}
                """.formatted(connectionJson, connectionJson, selectionJson);
        var state = MAPPER.readValue(stateJson, SessionMcpIdeStateResult.class);
        var connectionEvent = assertInstanceOf(IdeConnectionChangedEvent.class,
                parseJson(eventEnvelope("ide.connection_changed", stateJson)));
        var selectionEvent = assertInstanceOf(IdeSelectionChangedEvent.class,
                parseJson(eventEnvelope("ide.selection_changed", "{\"selection\": %s}".formatted(selectionJson))));

        IdeSelection selection = state.selection();
        IdeConnectedInfo connectedIde = state.connectedIde();
        IdeConnectedInfo retainedIde = state.retainedIde();
        IdeSelectionRange range = selection.selection();
        IdePosition start = range.start();
        IdePosition end = range.end();

        assertEquals("selected", selection.text());
        assertEquals("VS Code", connectedIde.ideName());
        assertEquals(selectionEvent.getData().selection(), selection);
        assertEquals(connectionEvent.getData().selection(), selection);
        assertEquals(connectionEvent.getData().connectedIde(), connectedIde);
        assertEquals(connectionEvent.getData().retainedIde(), retainedIde);
        assertEquals(selectionEvent.getData().selection().selection(), range);
        assertEquals(IdePosition.builder().line(2.0).character(3.0).build(), start);
        assertEquals(IdePosition.builder().line(4.0).character(5.0).build(), end);
    }

    // =========================================================================
    // Session Events
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"""
            {"kind":"status","state":"ready"}
            """, """
            {"kind":"startup","outcome":"skipped_disabled","fileCount":0,
             "startupDurationMs":1.25,"forcedByEnv":false,"warmStart":false,
             "disabledReason":"organization_policy_unknown","errorMessage":"startup diagnostic",
             "eligible":false}
            """, """
            {"kind":"server_error","errorType":"unexpected_exit","exitCode":0,
             "errorMessage":"server diagnostic"}
            """, """
            {"kind":"incremental","phase":"updated","changedFileCount":0,
             "addedFileCount":2,"deletedFileCount":1,"totalChangeCount":3,
             "walkDurationMs":0,"updateDurationMs":2.5,"totalDurationMs":3.75}
            """})
    void testIndexedSearchFullEnvelopeRoundTrip(String payload) throws Exception {
        String json = eventEnvelope("session.indexed_search", payload);

        var event = assertInstanceOf(SessionIndexedSearchEvent.class, parseJson(json));
        var data = assertInstanceOf(SessionIndexedSearchEvent.SessionIndexedSearchEventData.class, event.getData());
        var expected = MAPPER.readTree(json);
        assertEquals(expected.get("data"), data.raw());
        assertEquals(expected.get("data"), MAPPER.valueToTree(data));
        var serialized = MAPPER.writeValueAsString(event);
        assertEquals(expected, MAPPER.readTree(serialized));
        var reparsed = assertInstanceOf(SessionIndexedSearchEvent.class, parseJson(serialized));
        assertEquals(data, reparsed.getData());
    }

    @ParameterizedTest
    @ValueSource(strings = {"""
            {"kind":"policy_resolved","control":"filesystem","outcome":"degraded","toolCallId":null,
             "platform":"linux","backend":"bubblewrap","policySource":"user_policy",
             "enforcementPoint":"shell","readwritePathsCount":1,"readonlyPathsCount":0,"deniedPathsCount":1,
             "addCurrentWorkingDirectory":false,"allowOutbound":false,"allowLocalNetwork":false,
             "proxyMode":"none","allowBypass":false,"gitAuth":false,"ghAuth":false,"keychainAccess":false,
             "effectiveFilesystemPolicy":{"readwritePaths":["/workspace"],"readonlyPaths":[],
             "deniedPaths":["/private"]},"degradationReason":"denied_paths_unsupported"}
            """, """
            {"kind":"spawn_completed","control":"process","outcome":"succeeded","toolCallId":"tool-spawn",
             "platform":"macos","backend":"seatbelt","enforcementPoint":"shell","durationMs":1.25,
             "degradationReason":null}
            """, """
            {"kind":"enforcement_state","control":"process","outcome":"engaged","toolCallId":"tool-state",
             "platform":"windows","backend":"process_container","enforcementPoint":"shell",
             "attestation":"spawn_succeeded","command":"echo example"}
            """, """
            {"kind":"access_denied","control":"filesystem","outcome":"denied","toolCallId":"tool-denied",
             "platform":"linux","enforcementPoint":"builtin_filesystem","denialClass":"filesystem_read",
             "attestation":"builtin_policy_checked","confidence":"policy_corroborated",
             "deniedResource":"/private/example.txt","command":null,"processName":"example"}
            """, """
            {"kind":"bypass_decided","control":"bypass","outcome":"declined","toolCallId":"tool-bypass",
             "platform":"macos","enforcementPoint":"shell","source":"user_prompted",
             "denialClass":"network_outbound","confidence":"captured","deniedResource":"example.invalid",
             "command":"example --network","processName":"example"}
            """, """
            {"kind":"permissive_retry_decided","control":"bypass","outcome":"approved",
             "toolCallId":"tool-retry","platform":"linux","enforcementPoint":"shell",
             "source":"model_requested","denialClass":"filesystem_write","confidence":"sandbox_reported",
             "deniedResource":"/workspace/example.txt","command":"example --write","processName":"example"}
            """, """
            {"kind":"permissive_retry_completed","control":"process","outcome":"succeeded",
             "toolCallId":"tool-retry","platform":"linux","enforcementPoint":"shell",
             "denialClass":null,"confidence":null,"deniedResource":null,"command":"example --write",
             "processName":"example"}
            """})
    void testLegacySandboxDecisionFullEnvelopeRoundTrip(String payload) throws Exception {
        String json = eventEnvelope("sandbox.decision", payload);
        var event = assertInstanceOf(SandboxDecisionEvent.class, parseJson(json));
        var data = assertInstanceOf(SandboxDecisionEvent.SandboxDecisionEventData.class, event.getData());
        var expected = MAPPER.readTree(json);
        assertEquals(expected.get("data"), data.raw());
        assertEquals(expected.get("data"), MAPPER.valueToTree(data));
        var serialized = MAPPER.writeValueAsString(event);
        assertEquals(expected, MAPPER.readTree(serialized));
        var reparsed = assertInstanceOf(SandboxDecisionEvent.class, parseJson(serialized));
        assertEquals(data, reparsed.getData());
    }

    @Test
    void testOrdinaryObjectFullEnvelopeRoundTrip() throws Exception {
        String json = eventEnvelope("session.info", """
                {"infoType":"status","message":"Processing request"}
                """);
        var event = assertInstanceOf(SessionInfoEvent.class, parseJson(json));
        var data = assertInstanceOf(SessionInfoEvent.SessionInfoEventData.class, event.getData());
        assertEquals("status", data.infoType());
        assertEquals("Processing request", data.message());
        var serialized = MAPPER.writeValueAsString(event);
        assertEquals(MAPPER.readTree(json), MAPPER.readTree(serialized));
        assertEquals(data, assertInstanceOf(SessionInfoEvent.class, parseJson(serialized)).getData());
    }

    @Test
    void testEmptyObjectFullEnvelopeRoundTrip() throws Exception {
        String json = eventEnvelope("session.todos_changed", "{}");
        var event = assertInstanceOf(SessionTodosChangedEvent.class, parseJson(json));
        var data = assertInstanceOf(SessionTodosChangedEvent.SessionTodosChangedEventData.class, event.getData());
        assertEquals(SessionTodosChangedEvent.SessionTodosChangedEventData.builder().build(), data);
        assertEquals("{}", MAPPER.writeValueAsString(data));
        var serialized = MAPPER.writeValueAsString(event);
        assertEquals(MAPPER.readTree(json), MAPPER.readTree(serialized));
        assertEquals(data, assertInstanceOf(SessionTodosChangedEvent.class, parseJson(serialized)).getData());
    }

    @Test
    void testRootUnionDataNoArgConstructors() throws Exception {
        var indexedSearch = new SessionIndexedSearchEvent();
        indexedSearch.setData(new SessionIndexedSearchEvent.SessionIndexedSearchEventData());
        assertEquals(MAPPER.readTree("{}"), indexedSearch.getData().raw());
        assertEquals("{}", MAPPER.writeValueAsString(indexedSearch.getData()));

        var sandboxDecision = new SandboxDecisionEvent();
        sandboxDecision.setData(new SandboxDecisionEvent.SandboxDecisionEventData());
        assertEquals(MAPPER.readTree("{}"), sandboxDecision.getData().raw());
        assertEquals("{}", MAPPER.writeValueAsString(sandboxDecision.getData()));
    }

    @Test
    void testManagedSettingsResolvedClientProvenance() throws Exception {
        assertEquals("server", ManagedSettingsResolvedSource.SERVER.getValue());
        assertEquals("device", ManagedSettingsResolvedSource.DEVICE.getValue());
        assertEquals("client", ManagedSettingsResolvedSource.CLIENT.getValue());
        assertEquals("mixed", ManagedSettingsResolvedSource.MIXED.getValue());
        assertEquals("none", ManagedSettingsResolvedSource.NONE.getValue());

        String clientJson = """
                {
                    "type": "session.managed_settings_resolved",
                    "data": {
                        "source": "client",
                        "serverManaged": false,
                        "deviceManaged": false,
                        "clientManaged": true,
                        "failClosed": false,
                        "bypassPermissionsDisabled": true,
                        "managedKeys": ["permissions"]
                    }
                }
                """;

        var clientEvent = assertInstanceOf(SessionManagedSettingsResolvedEvent.class, parseJson(clientJson));
        assertEquals(ManagedSettingsResolvedSource.CLIENT, clientEvent.getData().source());
        assertEquals(Boolean.TRUE, clientEvent.getData().clientManaged());
        assertTrue(MAPPER.writeValueAsString(clientEvent).contains("\"clientManaged\":true"));

        String mixedJson = """
                {
                    "type": "session.managed_settings_resolved",
                    "data": {
                        "source": "mixed",
                        "serverManaged": true,
                        "deviceManaged": true,
                        "failClosed": false,
                        "bypassPermissionsDisabled": true,
                        "managedKeys": ["permissions"]
                    }
                }
                """;

        var mixedEvent = assertInstanceOf(SessionManagedSettingsResolvedEvent.class, parseJson(mixedJson));
        assertEquals(ManagedSettingsResolvedSource.MIXED, mixedEvent.getData().source());
        assertNull(mixedEvent.getData().clientManaged());
        assertFalse(MAPPER.writeValueAsString(mixedEvent).contains("\"clientManaged\""));
    }

    // =========================================================================
    // Tool Events
    // =========================================================================

    @Test
    void testToolStartCanonicalConstructorAcceptsNullOptionalArguments() throws Exception {
        var data = new ToolExecutionStartEvent.ToolExecutionStartEventData(null, "legacy-call", "shell", null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals("legacy-call", data.toolCallId());
        assertEquals("shell", data.toolName());
        assertNull(data.programToolCallId());
        assertTrue(data.$explicitNulls().isEmpty());
        assertEquals(MAPPER.readTree("{\"toolCallId\":\"legacy-call\",\"toolName\":\"shell\"}"),
                MAPPER.valueToTree(data));
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Test
    void testParseMissingTypeField() throws Exception {
        String json = """
                {
                    "data": {
                        "content": "Hello"
                    }
                }
                """;

        SessionEvent event = parseJson(json);
        assertNotNull(event, "Events without type field should return UnknownSessionEvent");
        assertInstanceOf(com.microsoft.copilot.generated.UnknownSessionEvent.class, event);
    }

    @Test
    void testParseEventWithUnknownFields() throws Exception {
        // Should not fail when there are extra unknown fields
        String json = """
                {
                    "type": "session.idle",
                    "data": {
                        "unknownField": "value",
                        "anotherUnknown": 123
                    },
                    "extraTopLevel": true
                }
                """;

        SessionEvent event = parseJson(json);
        assertNotNull(event, "Events with unknown fields should still parse");
        assertInstanceOf(SessionIdleEvent.class, event);
    }

    // =========================================================================
    // SessionEvent base fields
    // =========================================================================

    @Test
    void testParseBaseFieldsAllTogether() throws Exception {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String parentUuid = "660e8400-e29b-41d4-a716-446655440001";
        String json = """
                {
                    "type": "assistant.message",
                    "id": "%s",
                    "parentId": "%s",
                    "agentId": "subagent-1",
                    "ephemeral": false,
                    "timestamp": "2025-06-15T12:00:00+02:00",
                    "data": {
                        "content": "Hello"
                    }
                }
                """.formatted(uuid, parentUuid);

        SessionEvent event = parseJson(json);
        assertNotNull(event);
        assertEquals(UUID.fromString(uuid), event.getId());
        assertEquals(UUID.fromString(parentUuid), event.getParentId());
        assertEquals("subagent-1", event.getAgentId());
        assertFalse(event.getEphemeral());
        assertNotNull(event.getTimestamp());
        assertInstanceOf(AssistantMessageEvent.class, event);
        assertEquals("Hello", ((AssistantMessageEvent) event).getData().content());
    }

    // =========================================================================
    // Rich data field assertions
    // =========================================================================

    @Test
    void testSessionStartEventAllFields() throws Exception {
        String json = """
                {
                    "type": "session.start",
                    "data": {
                        "sessionId": "sess-full",
                        "version": 2.0,
                        "producer": "coding-agent",
                        "copilotVersion": "1.2.3",
                        "startTime": "2025-03-01T08:00:00Z",
                        "selectedModel": "gpt-4-turbo"
                    }
                }
                """;

        var event = (SessionStartEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertEquals("sess-full", data.sessionId());
        assertEquals((Long) 2L, data.version());
        assertEquals("coding-agent", data.producer());
        assertEquals("1.2.3", data.copilotVersion());
        assertNotNull(data.startTime());
        assertEquals("gpt-4-turbo", data.selectedModel());
    }

    @Test
    void testSessionCompactionCompleteEventAllFields() throws Exception {
        String json = """
                {
                    "type": "session.compaction_complete",
                    "data": {
                        "success": true,
                        "error": null,
                        "preCompactionTokens": 150000.0,
                        "postCompactionTokens": 60000.0,
                        "preCompactionMessagesLength": 100.0,
                        "messagesRemoved": 50.0,
                        "tokensRemoved": 90000.0,
                        "summaryContent": "Compacted conversation",
                        "checkpointNumber": 3.0,
                        "checkpointPath": "/checkpoints/3",
                        "compactionTokensUsed": {
                            "inputTokens": 1000,
                            "outputTokens": 500,
                            "cacheReadTokens": 200
                        },
                        "requestId": "req-compact-1"
                    }
                }
                """;

        var event = (SessionCompactionCompleteEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertTrue(data.success());
        assertNull(data.error());
        assertEquals((Long) 150000L, data.preCompactionTokens());
        assertEquals((Long) 60000L, data.postCompactionTokens());
        assertEquals((Long) 100L, data.preCompactionMessagesLength());
        assertEquals((Long) 50L, data.messagesRemoved());
        assertEquals((Long) 90000L, data.tokensRemoved());
        assertEquals("Compacted conversation", data.summaryContent());
        assertEquals((Long) 3L, data.checkpointNumber());
        assertEquals("/checkpoints/3", data.checkpointPath());
        assertEquals("req-compact-1", data.requestId());

        var tokens = data.compactionTokensUsed();
        assertNotNull(tokens);
        assertEquals((Long) 1000L, tokens.inputTokens());
        assertEquals((Long) 500L, tokens.outputTokens());
        assertEquals((Long) 200L, tokens.cacheReadTokens());
    }

    @Test
    void testSessionShutdownEventAllFields() throws Exception {
        String json = """
                {
                    "type": "session.shutdown",
                    "data": {
                        "shutdownType": "error",
                        "errorReason": "OOM",
                        "totalPremiumRequests": 10,
                        "totalApiDurationMs": 5000.5,
                        "sessionStartTime": 1700000000000,
                        "codeChanges": {
                            "linesAdded": 50,
                            "linesRemoved": 20,
                            "filesModified": ["a.java", "b.java", "c.java"]
                        },
                        "modelMetrics": {
                            "gpt-4": {
                                "requests": {"count": 5.0, "cost": 2.5}
                            }
                        },
                        "currentModel": "gpt-4-turbo"
                    }
                }
                """;

        var event = (SessionShutdownEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertEquals(ShutdownType.ERROR, data.shutdownType());
        assertEquals("OOM", data.errorReason());
        assertEquals(Double.valueOf(10.0), data.totalPremiumRequests());
        assertEquals((Long) 5000L, data.totalApiDurationMs());
        assertEquals((Long) 1700000000000L, data.sessionStartTime());
        assertEquals("gpt-4-turbo", data.currentModel());
        assertNotNull(data.modelMetrics());

        var changes = data.codeChanges();
        assertNotNull(changes);
        assertEquals((Long) 50L, changes.linesAdded());
        assertEquals((Long) 20L, changes.linesRemoved());
        assertNotNull(changes.filesModified());
        assertEquals(3, changes.filesModified().size());
        assertEquals("a.java", changes.filesModified().get(0));
    }

    // =========================================================================
    // Assistant events - rich field assertions
    // =========================================================================

    @Test
    void testAssistantMessageEventAllFields() throws Exception {
        String json = """
                {
                    "type": "assistant.message",
                    "data": {
                        "messageId": "msg-rich",
                        "content": "Full response",
                        "toolRequests": [
                            {
                                "toolCallId": "tc-1",
                                "name": "read_file",
                                "arguments": {"path": "/tmp/file.txt"}
                            },
                            {
                                "toolCallId": "tc-2",
                                "name": "write_file",
                                "arguments": {"path": "/tmp/out.txt", "content": "hello"}
                            }
                        ],
                        "interactionId": "interaction-msg-1",
                        "reasoningOpaque": "opaque-data",
                        "reasoningText": "My reasoning",
                        "encryptedContent": "enc123"
                    }
                }
                """;

        var event = (AssistantMessageEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertEquals("msg-rich", data.messageId());
        assertEquals("Full response", data.content());
        assertEquals("interaction-msg-1", data.interactionId());
        assertEquals("opaque-data", data.reasoningOpaque());
        assertEquals("My reasoning", data.reasoningText());
        assertEquals("enc123", data.encryptedContent());

        assertNotNull(data.toolRequests());
        assertEquals(2, data.toolRequests().size());
        assertEquals("tc-1", data.toolRequests().get(0).toolCallId());
        assertEquals("read_file", data.toolRequests().get(0).name());
        assertNotNull(data.toolRequests().get(0).arguments());
        assertEquals("tc-2", data.toolRequests().get(1).toolCallId());
        assertEquals("write_file", data.toolRequests().get(1).name());
    }

    @Test
    void testAssistantUsageEventAllFields() throws Exception {
        String json = """
                {
                    "type": "assistant.usage",
                    "data": {
                        "model": "gpt-4-turbo",
                        "responseModel": "gpt-4-turbo-2024-04-09",
                        "inputTokens": 500,
                        "outputTokens": 200,
                        "cacheReadTokens": 50,
                        "cacheWriteTokens": 150,
                        "cost": 0.05,
                        "duration": 1234.5,
                        "initiator": "user",
                        "apiCallId": "api-1",
                        "providerCallId": "prov-1",
                        "quotaSnapshots": {
                            "premium": {
                                "entitlementRequests": 100.0,
                                "usedRequests": 25.0
                            },
                            "standard": {
                                "entitlementRequests": 500.0,
                                "usedRequests": 150.0
                            }
                        },
                        "copilotUsage": {
                            "totalNanoAiu": 1234567.0,
                            "tokenDetails": [
                                {
                                    "tokenType": "input",
                                    "tokenCount": 500.0,
                                    "batchSize": 100.0,
                                    "costPerBatch": 0.001
                                },
                                {
                                    "tokenType": "output",
                                    "tokenCount": 200.0,
                                    "batchSize": 100.0,
                                    "costPerBatch": 0.002
                                }
                            ]
                        }
                    }
                }
                """;

        var event = (AssistantUsageEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertEquals("gpt-4-turbo", data.model());
        assertEquals("gpt-4-turbo-2024-04-09", data.responseModel());
        assertEquals((Long) 500L, data.inputTokens());
        assertEquals((Long) 200L, data.outputTokens());
        assertEquals((Long) 50L, data.cacheReadTokens());
        assertEquals((Long) 150L, data.cacheWriteTokens());
        assertEquals(0.05, data.cost());
        assertEquals((Long) 1234L, data.duration());
        assertEquals("user", data.initiator());
        assertEquals("api-1", data.apiCallId());
        assertEquals("prov-1", data.providerCallId());
        assertNotNull(data.quotaSnapshots());
        assertEquals(2, data.quotaSnapshots().size());

        // Verify copilotUsage
        assertNotNull(data.copilotUsage());
        assertEquals(Double.valueOf(1234567.0), data.copilotUsage().totalNanoAiu());
        assertNotNull(data.copilotUsage().tokenDetails());
        assertEquals(2, data.copilotUsage().tokenDetails().size());
        assertEquals("input", data.copilotUsage().tokenDetails().get(0).tokenType());
        assertEquals((Long) 500L, data.copilotUsage().tokenDetails().get(0).tokenCount());
        assertEquals("output", data.copilotUsage().tokenDetails().get(1).tokenType());
    }

    @Test
    void testAssistantUsageEventBuilderOmitsResponseModel() throws Exception {
        var data = AssistantUsageEvent.AssistantUsageEventData.builder().model("gpt-4-turbo").inputTokens(500L)
                .providerCallId("provider-request").build();
        assertEquals("gpt-4-turbo", data.model());
        assertEquals((Long) 500L, data.inputTokens());
        assertEquals("provider-request", data.providerCallId());
        assertNull(data.responseModel());
        assertEquals(
                MAPPER.readTree(
                        "{\"model\":\"gpt-4-turbo\",\"inputTokens\":500,\"providerCallId\":\"provider-request\"}"),
                MAPPER.readTree(MAPPER.writeValueAsBytes(data)));
    }

    // =========================================================================
    // Tool events - rich field assertions
    // =========================================================================

    @Test
    void testToolExecutionStartEventAllFields() throws Exception {
        String json = """
                {
                    "type": "tool.execution_start",
                    "data": {
                        "toolCallId": "tc-start-1",
                        "toolName": "mcp_read_file",
                        "arguments": {"path": "/tmp/x.txt"},
                        "mcpServerName": "filesystem",
                        "mcpToolName": "read_file"
                    }
                }
                """;

        var event = (ToolExecutionStartEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertEquals("tc-start-1", data.toolCallId());
        assertEquals("mcp_read_file", data.toolName());
        assertNotNull(data.arguments());
        assertEquals("filesystem", data.mcpServerName());
        assertEquals("read_file", data.mcpToolName());
    }

    @Test
    void testToolExecutionCompleteEventWithError() throws Exception {
        String json = """
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "tc-err-1",
                        "success": false,
                        "model": "claude-3-5-sonnet",
                        "interactionId": "interaction-tool-1",
                        "isUserRequested": true,
                        "error": {
                            "message": "File not found",
                            "code": "ENOENT"
                        },
                        "toolTelemetry": {
                            "duration": 50,
                            "retries": 0
                        }
                    }
                }
                """;

        var event = (ToolExecutionCompleteEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertEquals("tc-err-1", data.toolCallId());
        assertFalse(data.success());
        assertEquals("claude-3-5-sonnet", data.model());
        assertEquals("interaction-tool-1", data.interactionId());
        assertTrue(data.isUserRequested());

        assertNotNull(data.error());
        assertEquals("File not found", data.error().message());
        assertEquals("ENOENT", data.error().code());

        assertNotNull(data.toolTelemetry());
        assertEquals(2, data.toolTelemetry().size());
    }

    // =========================================================================
    // User events - rich field assertions
    // =========================================================================

    @Test
    void testUserMessageEventAllFieldsWithAttachments() throws Exception {
        String json = """
                {
                    "type": "user.message",
                    "data": {
                        "content": "Please review this file",
                        "transformedContent": "Transformed: Please review this file",
                        "source": "editor",
                        "attachments": [
                            {
                                "type": "file",
                                "path": "/src/Main.java",
                                "filePath": "/full/src/Main.java",
                                "displayName": "Main.java",
                                "text": "public class Main {}",
                                "selection": {
                                    "start": { "line": 1, "character": 0 },
                                    "end": { "line": 5, "character": 10 }
                                }
                            }
                        ]
                    }
                }
                """;

        var event = (UserMessageEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertEquals("Please review this file", data.content());
        assertEquals("Transformed: Please review this file", data.transformedContent());
        assertEquals("editor", data.source());

        assertNotNull(data.attachments());
        assertEquals(1, data.attachments().size());

        @SuppressWarnings("unchecked")
        var att = (java.util.Map<String, Object>) data.attachments().get(0);
        assertEquals("file", att.get("type"));
        assertEquals("/src/Main.java", att.get("path"));
        assertEquals("/full/src/Main.java", att.get("filePath"));
        assertEquals("Main.java", att.get("displayName"));
        assertEquals("public class Main {}", att.get("text"));

        @SuppressWarnings("unchecked")
        var selection = (java.util.Map<String, Object>) att.get("selection");
        assertNotNull(selection);
        @SuppressWarnings("unchecked")
        var selStart = (java.util.Map<String, Object>) selection.get("start");
        @SuppressWarnings("unchecked")
        var selEnd = (java.util.Map<String, Object>) selection.get("end");
        assertNotNull(selStart);
        assertNotNull(selEnd);
        assertEquals(1, ((Number) selStart.get("line")).intValue());
        assertEquals(0, ((Number) selStart.get("character")).intValue());
        assertEquals(5, ((Number) selEnd.get("line")).intValue());
        assertEquals(10, ((Number) selEnd.get("character")).intValue());
    }

    // =========================================================================
    // Subagent events - rich field assertions
    // =========================================================================

    @Test
    void testSubagentSelectedEventAllFields() throws Exception {
        String json = """
                {
                    "type": "subagent.selected",
                    "data": {
                        "agentName": "best-agent",
                        "agentDisplayName": "Best Agent",
                        "tools": ["read", "write", "search"]
                    }
                }
                """;

        var event = (SubagentSelectedEvent) parseJson(json);
        assertNotNull(event);
        var data = event.getData();
        assertEquals("best-agent", data.agentName());
        assertEquals("Best Agent", data.agentDisplayName());
        assertNotNull(data.tools());
        assertEquals(3, data.tools().size());
        assertEquals("read", data.tools().get(0));
        assertEquals("write", data.tools().get(1));
        assertEquals("search", data.tools().get(2));
    }

    // =========================================================================
    // Null / missing data scenarios
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"{\"type\":\"session.idle\",\"data\":null}", "{\"type\":\"session.idle\"}"})
    void testParseEventWithNullOrMissingData(String json) throws Exception {
        SessionEvent event = parseJson(json);
        assertNotNull(event);
        assertInstanceOf(SessionIdleEvent.class, event);
    }

    // =========================================================================
    // Additional data assertion tests
    // =========================================================================

    @Test
    void testParsePermissionRequestedEvent() throws Exception {
        String json = """
                {
                    "type": "permission.requested",
                    "data": {
                        "requestId": "perm-req-456",
                        "permissionRequest": {
                            "kind": "shell",
                            "toolCallId": "call-001"
                        }
                    }
                }
                """;

        var event = (PermissionRequestedEvent) parseJson(json);
        assertNotNull(event);
        assertEquals("permission.requested", event.getType());
        assertEquals("perm-req-456", event.getData().requestId());
        assertNotNull(event.getData().permissionRequest());
        @SuppressWarnings("unchecked")
        var permReq = (java.util.Map<String, Object>) event.getData().permissionRequest();
        assertEquals("shell", permReq.get("kind"));
    }

    @Test
    void testParseExitPlanModeRequestedEvent() throws Exception {
        String json = """
                {
                    "type": "exit_plan_mode.requested",
                    "data": {
                        "requestId": "plan-req-001",
                        "summary": "Plan is ready",
                        "planContent": "## Plan\\n1. Do thing",
                        "actions": ["exit_only", "interactive", "autopilot"],
                        "recommendedAction": "interactive"
                    }
                }
                """;

        var event = (ExitPlanModeRequestedEvent) parseJson(json);
        assertNotNull(event);
        assertEquals("exit_plan_mode.requested", event.getType());
        assertEquals("plan-req-001", event.getData().requestId());
        assertEquals("Plan is ready", event.getData().summary());
        assertEquals(3, event.getData().actions().size());
        assertEquals(ExitPlanModeAction.INTERACTIVE, event.getData().recommendedAction());
    }

    @Test
    void testParseElicitationRequestedEvent() throws Exception {
        String json = """
                {
                    "type": "elicitation.requested",
                    "data": {
                        "requestId": "elix-001",
                        "toolCallId": "tc-123",
                        "elicitationSource": "mcp_tool",
                        "message": "Please provide your name",
                        "mode": "form",
                        "requestedSchema": {
                            "type": "object",
                            "properties": {
                                "name": {"type": "string"}
                            },
                            "required": ["name"]
                        },
                        "url": null
                    }
                }
                """;

        SessionEvent event = parseJson(json);
        assertNotNull(event);
        assertInstanceOf(ElicitationRequestedEvent.class, event);
        assertEquals("elicitation.requested", event.getType());

        var castedEvent = (ElicitationRequestedEvent) event;
        assertNotNull(castedEvent.getData());
        assertEquals("elix-001", castedEvent.getData().requestId());
        assertEquals("tc-123", castedEvent.getData().toolCallId());
        assertEquals("mcp_tool", castedEvent.getData().elicitationSource());
        assertEquals("Please provide your name", castedEvent.getData().message());
        assertEquals(ElicitationRequestedMode.FORM, castedEvent.getData().mode());
        assertNotNull(castedEvent.getData().requestedSchema());
        assertEquals("object", castedEvent.getData().requestedSchema().type());
        assertNotNull(castedEvent.getData().requestedSchema().properties());
        assertNotNull(castedEvent.getData().requestedSchema().required());
        assertTrue(castedEvent.getData().requestedSchema().required().contains("name"));

        // Verify setData round-trip
        castedEvent.setData(ElicitationRequestedEvent.ElicitationRequestedEventData.builder().requestId("elix-002")
                .toolCallId(null).elicitationSource(null).elicitationSourceDisplayName(null).message("Enter URL")
                .mode(ElicitationRequestedMode.URL).requestedSchema(null).url("https://example.com").build());
        assertEquals("elix-002", castedEvent.getData().requestId());
        assertEquals(ElicitationRequestedMode.URL, castedEvent.getData().mode());
    }

}
