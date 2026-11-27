/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.tool.Param;

/**
 * Unit tests for {@link ParamSchema} — runtime JSON Schema generation from
 * {@link Param} descriptors.
 */
class ParamSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── buildSchema: empty / zero params ─────────────────────────────────────────

    @Test
    void buildSchema_nullOrEmptyParams_returnsEmptySchema() {
        for (Map<String, Object> schema : List.of(ParamSchema.buildSchema("tool", MAPPER, (Param<?>[]) null),
                ParamSchema.buildSchema("tool", MAPPER))) {
            assertEquals("object", schema.get("type"));
            assertTrue(((Map<?, ?>) schema.get("properties")).isEmpty());
            assertTrue(((List<?>) schema.get("required")).isEmpty());
        }
    }

    // ── buildSchema: validation ──────────────────────────────────────────────────

    @Test
    void buildSchema_nullParamElement_throwsWithToolName() {
        Param<String> p1 = Param.of(String.class, "a", "First");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ParamSchema.buildSchema("my_tool", MAPPER, p1, null));
        assertTrue(ex.getMessage().contains("my_tool"));
    }

    @Test
    void buildSchema_duplicateNames_throwsWithToolNameAndParamName() {
        Param<String> p1 = Param.of(String.class, "name", "First name");
        Param<String> p2 = Param.of(String.class, "name", "Second name");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ParamSchema.buildSchema("greeting", MAPPER, p1, p2));
        assertTrue(ex.getMessage().contains("name"));
        assertTrue(ex.getMessage().contains("greeting"));
    }

    // ── buildSchema: required / optional semantics ───────────────────────────────

    @Test
    void buildSchema_mixedRequiredAndOptional_onlyRequiredInList() {
        Param<String> pReq = Param.of(String.class, "query", "Search query");
        Param<Integer> pOpt = Param.of(Integer.class, "limit", "Max", false, "20");
        Map<String, Object> schema = ParamSchema.buildSchema("search", MAPPER, pReq, pOpt);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertEquals(1, required.size());
        assertEquals("query", required.get(0));
    }

    // ── buildSchema: description and default in property ─────────────────────────

    @Test
    void buildSchema_paramDescription_appearsInPropertySchema() {
        Param<String> p = Param.of(String.class, "msg", "A message to send");
        Map<String, Object> schema = ParamSchema.buildSchema("send", MAPPER, p);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> msgSchema = (Map<String, Object>) props.get("msg");
        assertEquals("A message to send", msgSchema.get("description"));
    }

    @Test
    void buildSchema_paramDefaults_appearTypedInPropertySchema() {
        Param<Integer> count = Param.of(Integer.class, "count", "Item count", false, "5");
        Param<String> mode = Param.of(String.class, "mode", "Operating mode", false, "fast");
        Param<Boolean> verbose = Param.of(Boolean.class, "verbose", "Verbose mode", false, "true");
        Map<String, Object> schema = ParamSchema.buildSchema("items", MAPPER, count, mode, verbose);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) schema.get("properties");
        assertEquals(5, props.get("count").get("default"));
        assertEquals("fast", props.get("mode").get("default"));
        assertEquals(true, props.get("verbose").get("default"));
    }

    // ── buildSchema: multiple params preserve order ──────────────────────────────

    @Test
    void buildSchema_multipleParams_orderPreservedInProperties() {
        Param<String> p1 = Param.of(String.class, "alpha", "First");
        Param<String> p2 = Param.of(String.class, "beta", "Second");
        Param<String> p3 = Param.of(String.class, "gamma", "Third");
        Map<String, Object> schema = ParamSchema.buildSchema("ordered", MAPPER, p1, p2, p3);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> keys = List.copyOf(props.keySet());
        assertEquals(List.of("alpha", "beta", "gamma"), keys);
    }

    // ── buildSchema: schema override ───────────────────────────────────────────

    @Test
    void buildSchema_withSchemaOverride_usesExplicitSchema() {
        Param<String> p = Param.of(String.class, "when", "Meeting time")
                .schema("{\"type\":\"string\",\"format\":\"date-time\"}");
        Map<String, Object> schema = ParamSchema.buildSchema("schedule", MAPPER, p);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> whenSchema = (Map<String, Object>) props.get("when");
        assertEquals("string", whenSchema.get("type"));
        assertEquals("date-time", whenSchema.get("format"));
        assertEquals("Meeting time", whenSchema.get("description"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("when"));
    }

    @Test
    void buildSchema_withSchemaOverride_rejectsTrailingJson() {
        Param<String> param = Param.of(String.class, "when", "Meeting time")
                .schema("{\"type\":\"string\"} {\"type\":\"integer\"}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ParamSchema.buildSchema("schedule", MAPPER, param));

        assertTrue(error.getMessage().contains("Invalid schema JSON"));
    }

    @Test
    void buildSchema_withSchemaOverride_preservesDecimalPrecision() {
        Param<String> param = Param.of(String.class, "value", "Precise value")
                .schema("{\"type\":\"number\",\"maximum\":1e400,\"multipleOf\":0.12345678901234567890}");

        Map<String, Object> schema = ParamSchema.buildSchema("calculate", MAPPER, param);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> valueSchema = (Map<String, Object>) properties.get("value");

        assertEquals(new BigDecimal("1e400"), valueSchema.get("maximum"));
        assertEquals(new BigDecimal("0.12345678901234567890"), valueSchema.get("multipleOf"));
    }

    // ── forType: type-mapping table ──────────────────────────────────────────────

    @Test
    void forType_mapsEachSupportedJavaTypeToItsJsonSchema() {
        Map<String, Object> integer = Map.of("type", "integer");
        Map<String, Object> number = Map.of("type", "number");
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> array = Map.of("type", "array");
        Map<String, Object> object = Map.of("type", "object");
        // {type, exact expected schema}
        Object[][] exact = {{int.class, integer}, {Integer.class, integer}, {long.class, integer},
                {Long.class, integer}, {short.class, integer}, {Short.class, integer}, {byte.class, integer},
                {Byte.class, integer}, {OptionalInt.class, integer}, {OptionalLong.class, integer},
                {double.class, number}, {Double.class, number}, {float.class, number}, {Float.class, number},
                {OptionalDouble.class, number}, {boolean.class, Map.of("type", "boolean")},
                {Boolean.class, Map.of("type", "boolean")}, {char.class, string}, {Character.class, string},
                {String.class, string}, {JsonNode.class, Map.of()}, {Object.class, Map.of()}, {List.class, array},
                {Set.class, array}, {Collection.class, array}, {Map.class, object}, {TestRecord.class, object},
                {TestPojo.class, object}};
        // {type, expected "type", expected "format"}
        Object[][] formatted = {{UUID.class, "string", "uuid"}, {OffsetDateTime.class, "string", "date-time"},
                {LocalDateTime.class, "string", "date-time"}, {Instant.class, "string", "date-time"},
                {ZonedDateTime.class, "string", "date-time"}, {LocalDate.class, "string", "date"},
                {LocalTime.class, "string", "time"}};
        // {array type, expected items "type"}
        Object[][] arrays = {{String[].class, "string"}, {int[].class, "integer"}, {double[].class, "number"}};

        List<String> mismatches = new ArrayList<>();
        for (Object[] row : exact) {
            Map<String, Object> actual = ParamSchema.forType((Class<?>) row[0]);
            if (!row[1].equals(actual)) {
                mismatches.add(row[0] + " -> " + actual);
            }
        }
        for (Object[] row : formatted) {
            Map<String, Object> actual = ParamSchema.forType((Class<?>) row[0]);
            if (!row[1].equals(actual.get("type")) || !row[2].equals(actual.get("format"))) {
                mismatches.add(row[0] + " -> " + actual);
            }
        }
        for (Object[] row : arrays) {
            Map<String, Object> actual = ParamSchema.forType((Class<?>) row[0]);
            Object items = actual.get("items");
            if (!"array".equals(actual.get("type")) || !(items instanceof Map<?, ?> m)
                    || !row[1].equals(m.get("type"))) {
                mismatches.add(row[0] + " -> " + actual);
            }
        }
        assertEquals(List.of(), mismatches);
    }

    @Test
    void forType_enum_returnsStringWithEnumValues() {
        Map<String, Object> schema = ParamSchema.forType(TestColor.class);
        assertEquals("string", schema.get("type"));
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) schema.get("enum");
        assertNotNull(values);
        assertEquals(List.of("RED", "GREEN", "BLUE"), values);
    }

    // ── Test helper types ────────────────────────────────────────────────────────

    enum TestColor {
        RED, GREEN, BLUE
    }

    record TestRecord(String name, int value) {
    }

    static class TestPojo {
        String field;
    }
}
