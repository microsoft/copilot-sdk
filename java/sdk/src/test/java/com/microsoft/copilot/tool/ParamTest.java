/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link Param} runtime parameter metadata.
 */
public class ParamTest {

    @Test
    void ofCreatesRequiredParamWithNoDefault() {
        Param<String> p = Param.of(String.class, "query", "Search query");
        assertEquals(String.class, p.type());
        assertEquals("query", p.name());
        assertEquals("Search query", p.description());
        assertTrue(p.required());
        assertEquals("", p.defaultValue());
        assertFalse(p.hasDefaultValue());
        assertEquals("", p.schema());
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,desc,name", "'  ',desc,name", "n,NULL,description",
            "n,'',description"}, nullValues = "NULL")
    void rejectsNullOrBlankNameAndDescription(String name, String description, String field) {
        var ex = assertThrows(IllegalArgumentException.class, () -> Param.of(String.class, name, description));
        assertTrue(ex.getMessage().contains(field));
    }

    @Test
    void rejectsRequiredWithNonEmptyDefault() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Param.of(String.class, "x", "desc", true, "val"));
        assertTrue(ex.getMessage().contains("required=true"));
    }

    @Test
    void allowsRequiredWithNullDefault() {
        Param<String> p = Param.of(String.class, "x", "desc", true, null);
        assertTrue(p.required());
        assertEquals("", p.defaultValue());
    }

    @ParameterizedTest
    @CsvSource({"java.lang.Integer,42,abc", "java.lang.Long,999999999999,notlong", "java.lang.Double,3.14,xyz",
            "java.lang.Float,1.5,notfloat", "java.lang.Short,100,99999", "java.lang.Byte,127,999",
            "java.lang.Boolean,true,yes", "java.lang.Boolean,FALSE,yes",
            "com.microsoft.copilot.tool.ParamTest$TestEnum,ALPHA,INVALID"})
    void validatesDefaultForEachSupportedType(Class<?> type, String valid, String invalid) {
        assertEquals(valid, Param.of(type, "v", "value", false, valid).defaultValue());
        assertThrows(IllegalArgumentException.class, () -> Param.of(type, "v", "value", false, invalid));
    }

    @Test
    void rejectsUnsupportedTypeWithDefault() {
        assertThrows(IllegalArgumentException.class, () -> Param.of(Object.class, "o", "object", false, "something"));
    }

    @Test
    void fluentCopiesLeaveOriginalUnchangedAndPreserveSchema() {
        Param<String> original = Param.of(String.class, "a", "desc1");
        assertEquals("b", original.name("b").name());
        assertEquals("desc2", original.description("desc2").description());
        assertFalse(original.required(false).required());
        assertEquals("a", original.name());
        assertEquals("desc1", original.description());
        assertTrue(original.required());

        Param<String> base = original.schema("{\"type\":\"string\"}");
        assertEquals("{\"type\":\"string\"}", base.name("y").schema());
        assertEquals("{\"type\":\"string\"}", base.description("other").schema());
        assertEquals("{\"type\":\"string\"}", base.required(false).schema());
    }

    @Test
    void defaultValueMutatorSetsOptional() {
        Param<String> original = Param.of(String.class, "a", "desc");
        Param<String> withDefault = original.defaultValue("val");
        assertTrue(original.required());
        assertFalse(withDefault.required());
        assertEquals("val", withDefault.defaultValue());
        assertTrue(withDefault.hasDefaultValue());
    }

    @Test
    void equalityUsesAllMetadata() {
        Param<String> a = Param.of(String.class, "x", "desc");
        Param<String> b = Param.of(String.class, "x", "desc");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, Param.of(String.class, "y", "desc"));
    }

    @Test
    void rejectsSchemaWithDefaultValue() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> Param.of(String.class, "x", "desc").schema("{\"type\":\"string\"}").defaultValue("hello"));
        assertTrue(ex.getMessage().contains("schema"));
        assertTrue(ex.getMessage().contains("defaultValue"));
    }

    @ParameterizedTest
    @CsvSource(value = {"not json", "{\"type\":\"string\""}, delimiter = '|')
    void rejectsSchemaThatIsNotAJsonObjectString(String schema) {
        var ex = assertThrows(IllegalArgumentException.class, () -> Param.of(String.class, "x", "desc").schema(schema));
        assertTrue(ex.getMessage().contains("schema"));
    }

    @Test
    void acceptsValidSchemaJson() {
        Param<String> p = Param.of(String.class, "x", "desc").schema("{\"type\":\"string\",\"format\":\"date-time\"}");
        assertEquals("{\"type\":\"string\",\"format\":\"date-time\"}", p.schema());
    }

    @Test
    void rejectsNullType() {
        assertThrows(NullPointerException.class, () -> Param.of(null, "n", "desc"));
    }

    enum TestEnum {
        ALPHA, BETA
    }
}
