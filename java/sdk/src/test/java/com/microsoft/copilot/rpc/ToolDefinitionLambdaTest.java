/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.copilot.AllowCopilotExperimental;
import com.microsoft.copilot.tool.Param;

/**
 * Unit tests for {@link ToolDefinition#from}, {@link ToolDefinition#fromAsync},
 * {@link ToolDefinition#fromWithToolInvocation}, and
 * {@link ToolDefinition#fromAsyncWithToolInvocation} lambda-tool factories,
 * plus the fluent option-modifier methods
 * ({@link ToolDefinition#overridesBuiltInTool},
 * {@link ToolDefinition#skipPermission}, {@link ToolDefinition#defer}).
 *
 * <p>
 * Tests are grouped by the Phase 4.4 contract:
 * <ol>
 * <li>Successful inline definitions for arities 0–2 (sync and async).</li>
 * <li>ToolInvocation context injection (sync and async).</li>
 * <li>Option flag propagation.</li>
 * <li>Required/default semantics.</li>
 * <li>Error and validation paths.</li>
 * <li>Schema structure.</li>
 * <li>Result formatting (String, null, non-String).</li>
 * <li>Argument coercion.</li>
 * </ol>
 */
@AllowCopilotExperimental
class ToolDefinitionLambdaTest {

    private record CustomDateTime(String value) {
    }

    private record Reading(String sensor, Double value) {
    }

    record Tagged(String title, Optional<String> tag) {
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static ToolInvocation invocationOf(Map<String, ?> args) {
        ObjectNode argsNode = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, ?> e : args.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                argsNode.put(e.getKey(), s);
            } else if (v instanceof Integer i) {
                argsNode.put(e.getKey(), i);
            } else if (v instanceof Long l) {
                argsNode.put(e.getKey(), l);
            } else if (v instanceof Double d) {
                argsNode.put(e.getKey(), d);
            } else if (v instanceof Boolean b) {
                argsNode.put(e.getKey(), b);
            } else if (v != null) {
                argsNode.put(e.getKey(), v.toString());
            }
        }
        return new ToolInvocation().setArguments(argsNode);
    }

    private static ToolInvocation invocationWithContext(String sessionId, String toolCallId, Map<String, ?> args) {
        return invocationOf(args).setSessionId(sessionId).setToolCallId(toolCallId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaOf(ToolDefinition tool) {
        return (Map<String, Object>) tool.parameters();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(ToolDefinition tool) {
        return (Map<String, Object>) schemaOf(tool).get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(ToolDefinition tool) {
        return (List<String>) schemaOf(tool).get("required");
    }

    // ── Group 1: Successful inline definitions – arity 0, sync ───────────────────

    @Test
    void from_zeroArg_invokesHandler() throws Exception {
        ToolDefinition tool = ToolDefinition.from("ping", "Returns pong", () -> "pong");
        assertEquals("ping", tool.name());
        assertEquals("Returns pong", tool.description());
        Object result = tool.handler().invoke(invocationOf(Map.of())).get();
        assertEquals("pong", result);
    }

    // ── Group 1: Successful inline definitions – arity 1, sync ───────────────────

    @Test
    void from_oneArg_invokesHandler() throws Exception {
        Param<String> nameParam = Param.of(String.class, "name", "The user's name");
        ToolDefinition tool = ToolDefinition.from("greet", "Greets a user", nameParam, n -> "Hello, " + n + "!");
        Object result = tool.handler().invoke(invocationOf(Map.of("name", "Alice"))).get();
        assertEquals("Hello, Alice!", result);
    }

    // ── Group 1: Successful inline definitions – arity 2, sync ───────────────────

    @Test
    void from_twoArg_invokesHandler() throws Exception {
        Param<Integer> paramA = Param.of(Integer.class, "a", "First number");
        Param<Integer> paramB = Param.of(Integer.class, "b", "Second number");
        ToolDefinition tool = ToolDefinition.from("add", "Adds two integers", paramA, paramB,
                (a, b) -> String.valueOf(a + b));
        Object result = tool.handler().invoke(invocationOf(Map.of("a", 3, "b", 4))).get();
        assertEquals("7", result);
    }

    @Test
    void from_twoArg_schemaBothParamsPresent() {
        Param<Integer> paramA = Param.of(Integer.class, "a", "First");
        Param<Integer> paramB = Param.of(Integer.class, "b", "Second");
        ToolDefinition tool = ToolDefinition.from("add", "Adds two integers", paramA, paramB, (a, b) -> a + b);
        assertTrue(propertiesOf(tool).containsKey("a"));
        assertTrue(propertiesOf(tool).containsKey("b"));
        assertTrue(requiredOf(tool).contains("a"));
        assertTrue(requiredOf(tool).contains("b"));
    }

    // ── Group 2: Async handlers (fromAsync) ──────────────────────────────────────

    @Test
    void fromAsync_zeroArg_invokesHandler() throws Exception {
        ToolDefinition tool = ToolDefinition.fromAsync("ping_async", "Async ping",
                () -> CompletableFuture.completedFuture("pong"));
        Object result = tool.handler().invoke(invocationOf(Map.of())).get();
        assertEquals("pong", result);
    }

    @Test
    void fromAsync_oneArg_invokesHandler() throws Exception {
        Param<String> nameParam = Param.of(String.class, "name", "Name to greet");
        ToolDefinition tool = ToolDefinition.fromAsync("greet_async", "Async greet", nameParam,
                n -> CompletableFuture.completedFuture("Hi, " + n + "!"));
        Object result = tool.handler().invoke(invocationOf(Map.of("name", "Bob"))).get();
        assertEquals("Hi, Bob!", result);
    }

    @Test
    void fromAsync_twoArg_invokesHandler() throws Exception {
        Param<Integer> paramA = Param.of(Integer.class, "a", "Left operand");
        Param<Integer> paramB = Param.of(Integer.class, "b", "Right operand");
        ToolDefinition tool = ToolDefinition.fromAsync("add_async", "Async add", paramA, paramB,
                (a, b) -> CompletableFuture.completedFuture(String.valueOf(a + b)));
        Object result = tool.handler().invoke(invocationOf(Map.of("a", 10, "b", 5))).get();
        assertEquals("15", result);
    }

    // ── Group 3: ToolInvocation context injection (sync) ─────────────────────────

    @Test
    void fromWithToolInvocation_zeroArg_receivesContext() throws Exception {
        ToolDefinition tool = ToolDefinition.fromWithToolInvocation("ctx_sync", "Returns session id",
                inv -> "session=" + inv.getSessionId());
        Object result = tool.handler().invoke(invocationWithContext("sess-1", "call-1", Map.of())).get();
        assertEquals("session=sess-1", result);
    }

    @Test
    void fromWithToolInvocation_oneArg_receivesArgAndContext() throws Exception {
        Param<String> phaseParam = Param.of(String.class, "phase", "Current phase");
        ToolDefinition tool = ToolDefinition.fromWithToolInvocation("report", "Report phase", phaseParam,
                (phase, inv) -> "phase=" + phase + ",callId=" + inv.getToolCallId());
        Object result = tool.handler().invoke(invocationWithContext("sess-2", "call-42", Map.of("phase", "analysis")))
                .get();
        assertEquals("phase=analysis,callId=call-42", result);
    }

    @Test
    void fromWithToolInvocation_oneArg_schemaExcludesInvocationParam() {
        Param<String> phaseParam = Param.of(String.class, "phase", "Current phase");
        ToolDefinition tool = ToolDefinition.fromWithToolInvocation("report", "Report phase", phaseParam,
                (phase, inv) -> phase);
        assertTrue(propertiesOf(tool).containsKey("phase"));
        assertFalse(propertiesOf(tool).containsKey("invocation"));
        assertEquals(List.of("phase"), requiredOf(tool));
    }

    // ── Group 4: Async ToolInvocation context injection ──────────────────────────

    @Test
    void fromAsyncWithToolInvocation_zeroArg_receivesContext() throws Exception {
        ToolDefinition tool = ToolDefinition.fromAsyncWithToolInvocation("ctx_async", "Async ctx",
                inv -> CompletableFuture.completedFuture("callId=" + inv.getToolCallId()));
        Object result = tool.handler().invoke(invocationWithContext("sess-3", "call-99", Map.of())).get();
        assertEquals("callId=call-99", result);
    }

    @Test
    void fromAsyncWithToolInvocation_oneArg_receivesArgAndContext() throws Exception {
        Param<String> phaseParam = Param.of(String.class, "phase", "Phase name");
        ToolDefinition tool = ToolDefinition.fromAsyncWithToolInvocation("report_async", "Async report", phaseParam,
                (phase, inv) -> CompletableFuture.completedFuture("phase=" + phase + ",sess=" + inv.getSessionId()));
        Object result = tool.handler().invoke(invocationWithContext("sess-4", "call-7", Map.of("phase", "planning")))
                .get();
        assertEquals("phase=planning,sess=sess-4", result);
    }

    // ── Group 5: Option flag propagation ─────────────────────────────────────────

    @Test
    void fluentModifiers_canBeChainedWithoutMutatingOriginal() {
        ToolDefinition base = ToolDefinition.from("override_tool", "Overrides built-in", () -> "ok");
        ToolDefinition tool = base.overridesBuiltInTool(true).skipPermission(true).defer(ToolDefer.AUTO);
        assertEquals(Boolean.TRUE, tool.overridesBuiltInTool());
        assertEquals(Boolean.TRUE, tool.skipPermission());
        assertEquals(ToolDefer.AUTO, tool.defer());
        assertEquals(ToolDefer.NEVER, base.defer(ToolDefer.NEVER).defer());
        assertNull(base.overridesBuiltInTool(), "original must remain unchanged");
        assertNull(base.skipPermission(), "original must remain unchanged");
        assertNull(base.defer(), "original must remain unchanged");
    }

    @Test
    void fluentModifiers_preserveHandlerAndSchema() throws Exception {
        Param<String> p = Param.of(String.class, "msg", "A message");
        ToolDefinition tool = ToolDefinition.from("echo", "Echoes message", p, msg -> msg).skipPermission(true)
                .overridesBuiltInTool(false);
        assertNotNull(tool.handler());
        Object result = tool.handler().invoke(invocationOf(Map.of("msg", "hello"))).get();
        assertEquals("hello", result);
    }

    // ── Group 6: Required/default semantics ──────────────────────────────────────

    @Test
    void requiredParam_missingFromInvocation_throwsIllegalArgumentException() {
        Param<String> p = Param.of(String.class, "word", "A required word");
        ToolDefinition tool = ToolDefinition.from("echo", "Echoes", p, w -> w);
        var ex = assertThrows(IllegalArgumentException.class, () -> tool.handler().invoke(invocationOf(Map.of())));
        assertTrue(ex.getMessage().contains("word"), "Exception message should mention the missing parameter name");
    }

    @Test
    void optionalParamWithDefault_absent_usesDefault() throws Exception {
        Param<Integer> p = Param.of(Integer.class, "limit", "Max results", false, "10");
        ToolDefinition tool = ToolDefinition.from("list", "Lists items", p, lim -> "limit=" + lim);
        Object result = tool.handler().invoke(invocationOf(Map.of())).get();
        assertEquals("limit=10", result);
    }

    @Test
    void optionalParam_absent_noDefaultYieldsNull() throws Exception {
        Param<String> p = Param.of(String.class, "title", "Optional title", false, "");
        ToolDefinition tool = ToolDefinition.from("greet", "Greets", p, t -> t == null ? "(no title)" : t);
        Object result = tool.handler().invoke(invocationOf(Map.of())).get();
        assertEquals("(no title)", result);
    }

    // ── Group 7: Error / validation paths ────────────────────────────────────────

    @Test
    void factories_rejectNullOrBlankNameAndDescription() {
        assertThrows(IllegalArgumentException.class, () -> ToolDefinition.from(null, "desc", () -> "ok"));
        assertThrows(IllegalArgumentException.class, () -> ToolDefinition.from("  ", "desc", () -> "ok"));
        assertThrows(IllegalArgumentException.class, () -> ToolDefinition.from("tool", null, () -> "ok"));
        assertThrows(IllegalArgumentException.class, () -> ToolDefinition.from("tool", "", () -> "ok"));
        assertThrows(IllegalArgumentException.class,
                () -> ToolDefinition.fromAsync(null, "desc", () -> CompletableFuture.completedFuture("ok")));
        assertThrows(IllegalArgumentException.class,
                () -> ToolDefinition.fromWithToolInvocation(null, "desc", inv -> "ok"));
        assertThrows(IllegalArgumentException.class, () -> ToolDefinition.fromAsyncWithToolInvocation("tool", null,
                inv -> CompletableFuture.completedFuture("ok")));
    }

    @Test
    void from_nullHandler_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolDefinition.from("tool", "desc", (java.util.function.Supplier<String>) null));
    }

    // ── Group 8: Schema structure
    // ─────────────────────────────────────────────────

    @Test
    void schema_oneArg_hasCorrectTypeForString() {
        Param<String> p = Param.of(String.class, "query", "Search query");
        ToolDefinition tool = ToolDefinition.from("search", "Searches", p, q -> q);
        @SuppressWarnings("unchecked")
        Map<String, Object> querySchema = (Map<String, Object>) propertiesOf(tool).get("query");
        assertNotNull(querySchema);
        assertEquals("string", querySchema.get("type"));
        assertEquals("Search query", querySchema.get("description"));
    }

    @Test
    void schema_oneArg_customTypeUsesExplicitSchemaAndCoercion() throws Exception {
        Param<CustomDateTime> p = Param.of(CustomDateTime.class, "when", "Meeting time")
                .schema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}");
        ToolDefinition tool = ToolDefinition.from("schedule", "Schedules a meeting", p,
                when -> "scheduled " + when.value());
        @SuppressWarnings("unchecked")
        Map<String, Object> whenSchema = (Map<String, Object>) propertiesOf(tool).get("when");
        assertNotNull(whenSchema);
        assertEquals("object", whenSchema.get("type"));
        assertEquals("Meeting time", whenSchema.get("description"));
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.putObject("when").put("value", "2026-07-23T21:00:00Z");
        Object result = tool.handler().invoke(new ToolInvocation().setArguments(arguments)).get();
        assertEquals("scheduled 2026-07-23T21:00:00Z", result);
    }

    // ── Group 9: Result formatting
    // ────────────────────────────────────────────────

    @Test
    void resultFormatting_nullMappedToSuccess() throws Exception {
        ToolDefinition tool = ToolDefinition.from("noop", "No-op", () -> null);
        Object result = tool.handler().invoke(invocationOf(Map.of())).get();
        assertEquals("Success", result);
    }

    @Test
    void resultFormatting_nonStringSerializedToJson() throws Exception {
        Param<String> p = Param.of(String.class, "key", "Key name");
        ToolDefinition tool = ToolDefinition.from("to_map", "Wraps in map", p, k -> Map.of("key", k, "value", 42));
        Object result = tool.handler().invoke(invocationOf(Map.of("key", "x"))).get();
        assertNotNull(result);
        assertTrue(result instanceof String, "Non-String should be JSON-serialized to String");
        String json = (String) result;
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        assertTrue(node.isObject(), "Result should be a JSON object");
        assertEquals("x", node.get("key").asText(), "JSON must contain key field with value 'x'");
        assertEquals(42, node.get("value").asInt(), "JSON must contain value field with value 42");
    }

    @Test
    void resultFormatting_integerSerializedToJson() throws Exception {
        ToolDefinition tool = ToolDefinition.from("forty_two", "Returns 42", () -> 42);
        Object result = tool.handler().invoke(invocationOf(Map.of())).get();
        assertEquals("42", result);
    }

    @Test
    void resultFormatting_mapKeepsNullValue() throws Exception {
        ToolDefinition tool = ToolDefinition.from("status", "Returns a status map", () -> {
            Map<String, Object> status = new HashMap<>();
            status.put("status", "ok");
            status.put("error", null);
            return status;
        });
        Object result = tool.handler().invoke(invocationOf(Map.of())).get();
        JsonNode node = new ObjectMapper().readTree((String) result);
        assertEquals("ok", node.get("status").asText());
        assertTrue(node.has("error"), "A null map value must be serialized, got: " + result);
        assertTrue(node.get("error").isNull());
    }

    @Test
    void resultFormatting_recordOmitsNullComponent() throws Exception {
        ToolDefinition tool = ToolDefinition.from("reading", "Returns a reading", () -> new Reading("s1", null));
        Object result = tool.handler().invoke(invocationOf(Map.of())).get();
        assertEquals("{\"sensor\":\"s1\"}", result);
    }

    // ── Group 10: Argument coercion
    // ───────────────────────────────────────────────

    @Test
    void coercion_booleanArg() throws Exception {
        Param<Boolean> p = Param.of(Boolean.class, "flag", "A flag");
        ToolDefinition tool = ToolDefinition.from("flagged", "Reports flag", p, f -> f ? "yes" : "no");
        Object result = tool.handler().invoke(invocationOf(Map.of("flag", true))).get();
        assertEquals("yes", result);
    }

    @Test
    void coercion_enumArgFromString() throws Exception {
        Param<Color> p = Param.of(Color.class, "color", "A color");
        ToolDefinition tool = ToolDefinition.from("paint", "Paints", p, c -> c.name().toLowerCase());
        Object result = tool.handler().invoke(invocationOf(Map.of("color", "GREEN"))).get();
        assertEquals("green", result);
    }

    @Test
    void coercion_mapArgKeepsExplicitNull() throws Exception {
        @SuppressWarnings("rawtypes")
        Param<Map> p = Param.of(Map.class, "filter", "A filter map");
        ToolDefinition tool = ToolDefinition.from("apply_filter", "Applies a filter", p,
                filter -> filter.containsKey("owner") + ":" + filter.get("owner") + ":" + filter.size());
        ObjectNode filter = JsonNodeFactory.instance.objectNode();
        filter.putNull("owner");
        filter.put("limit", 5);
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.set("filter", filter);
        Object result = tool.handler().invoke(new ToolInvocation().setArguments(args)).get();
        assertEquals("true:null:2", result);
    }

    @Test
    void coercion_recordArgWithOptionalComponent() throws Exception {
        Param<Tagged> p = Param.of(Tagged.class, "note", "A note");
        ToolDefinition tool = ToolDefinition.from("save", "Saves a note", p, n -> n.title() + " tag=" + n.tag());
        // {note argument, expected handler result}
        String[][] rows = {{"{\"title\":\"a\",\"tag\":\"b\"}", "a tag=Optional[b]"},
                {"{\"title\":\"a\"}", "a tag=Optional.empty"},
                {"{\"title\":\"a\",\"tag\":null}", "a tag=Optional.empty"}};
        ObjectMapper json = new ObjectMapper();
        List<String> mismatches = new ArrayList<>();
        for (String[] row : rows) {
            ObjectNode args = JsonNodeFactory.instance.objectNode();
            args.set("note", json.readTree(row[0]));
            String actual;
            try {
                actual = String.valueOf(tool.handler().invoke(new ToolInvocation().setArguments(args)).get());
            } catch (Exception e) {
                actual = "threw " + e;
            }
            if (!row[1].equals(actual)) {
                mismatches.add(row[0] + ": expected <" + row[1] + "> but was <" + actual + ">");
            }
        }
        assertEquals(List.of(), mismatches);
    }

    @Test
    void resultFormatting_recordWithOptionalComponent() throws Exception {
        ToolDefinition present = ToolDefinition.from("present", "Returns a tagged note",
                () -> new Tagged("a", Optional.of("b")));
        ToolDefinition empty = ToolDefinition.from("empty", "Returns an untagged note",
                () -> new Tagged("a", Optional.empty()));
        assertEquals("{\"title\":\"a\",\"tag\":\"b\"}", present.handler().invoke(invocationOf(Map.of())).get());
        assertEquals("{\"title\":\"a\",\"tag\":null}", empty.handler().invoke(invocationOf(Map.of())).get());
    }

    // ── Inner types for test helpers
    // ──────────────────────────────────────────────

    enum Color {
        RED, GREEN, BLUE
    }
}
