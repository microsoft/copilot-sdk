/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.tool.Param;

/**
 * Unit tests for {@link ParamCoercion} — runtime argument coercion from raw
 * invocation maps to typed Java values declared by {@link Param} descriptors.
 */
class ParamCoercionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Note: enum arguments via mapper.convertValue require the enum's package
    // to be opened to Jackson; SDK test enums are not, so enum defaults
    // (Enum.valueOf) are covered by coerce_optionalWithEnumDefault_usesDefault.

    // ── coerce: Optional primitive types ─────────────────────────────────────────

    @Test
    void coerce_optionalInt_fromNumber() {
        Param<OptionalInt> p = Param.of(OptionalInt.class, "count", "Count", false, "");
        OptionalInt result = ParamCoercion.coerce(Map.of("count", 7), p, MAPPER);
        assertEquals(OptionalInt.of(7), result);
    }

    @Test
    void coerce_optionalLong_fromNumber() {
        Param<OptionalLong> p = Param.of(OptionalLong.class, "ts", "Timestamp", false, "");
        OptionalLong result = ParamCoercion.coerce(Map.of("ts", 999L), p, MAPPER);
        assertEquals(OptionalLong.of(999L), result);
    }

    @Test
    void coerce_optionalDouble_fromNumber() {
        Param<OptionalDouble> p = Param.of(OptionalDouble.class, "ratio", "Ratio", false, "");
        OptionalDouble result = ParamCoercion.coerce(Map.of("ratio", 2.5), p, MAPPER);
        assertEquals(OptionalDouble.of(2.5), result);
    }

    @Test
    void coerce_optionalInt_nonNumeric_throwsIllegalArgument() {
        Param<OptionalInt> p = Param.of(OptionalInt.class, "count", "Count", false, "");
        assertThrows(IllegalArgumentException.class,
                () -> ParamCoercion.coerce(Map.of("count", "not_a_number"), p, MAPPER));
    }

    @Test
    void coerce_optionalLong_nonNumeric_throwsIllegalArgument() {
        Param<OptionalLong> p = Param.of(OptionalLong.class, "ts", "Timestamp", false, "");
        assertThrows(IllegalArgumentException.class, () -> ParamCoercion.coerce(Map.of("ts", "abc"), p, MAPPER));
    }

    @Test
    void coerce_optionalDouble_nonNumeric_throwsIllegalArgument() {
        Param<OptionalDouble> p = Param.of(OptionalDouble.class, "ratio", "Ratio", false, "");
        assertThrows(IllegalArgumentException.class, () -> ParamCoercion.coerce(Map.of("ratio", "xyz"), p, MAPPER));
    }

    // ── coerce: missing argument — required ──────────────────────────────────────

    @Test
    void coerce_requiredMissing_nullArgs_throws() {
        Param<String> p = Param.of(String.class, "name", "A name");
        var ex = assertThrows(IllegalArgumentException.class, () -> ParamCoercion.coerce(null, p, MAPPER));
        assertTrue(ex.getMessage().contains("name"));
    }

    // ── coerce: missing argument — optional with default ─────────────────────────

    @Test
    void coerce_optionalWithStringDefault_usesDefault() {
        Param<String> p = Param.of(String.class, "mode", "Mode", false, "normal");
        String result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals("normal", result);
    }

    @Test
    void coerce_optionalWithLongDefault_usesDefault() {
        Param<Long> p = Param.of(Long.class, "offset", "Offset", false, "100");
        Long result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals(100L, result);
    }

    @Test
    void coerce_optionalWithDoubleDefault_usesDefault() {
        Param<Double> p = Param.of(Double.class, "threshold", "Threshold", false, "0.75");
        Double result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals(0.75, result, 0.001);
    }

    @Test
    void coerce_optionalWithFloatDefault_usesDefault() {
        Param<Float> p = Param.of(Float.class, "rate", "Rate", false, "1.5");
        Float result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals(1.5f, result, 0.01f);
    }

    @Test
    void coerce_optionalWithShortDefault_usesDefault() {
        Param<Short> p = Param.of(Short.class, "level", "Level", false, "3");
        Short result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals((short) 3, result);
    }

    @Test
    void coerce_optionalWithByteDefault_usesDefault() {
        Param<Byte> p = Param.of(Byte.class, "code", "Code", false, "7");
        Byte result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals((byte) 7, result);
    }

    @Test
    void coerce_optionalWithBooleanDefault_usesDefault() {
        Param<Boolean> p = Param.of(Boolean.class, "verbose", "Verbose", false, "true");
        Boolean result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals(true, result);
        Param<Boolean> off = Param.of(Boolean.class, "verbose", "Verbose", false, "false");
        assertEquals(false, ParamCoercion.coerce(Map.of(), off, MAPPER));
    }

    @Test
    void coerce_optionalWithEnumDefault_usesDefault() {
        Param<TestMode> p = Param.of(TestMode.class, "mode", "Mode", false, "SLOW");
        TestMode result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals(TestMode.SLOW, result);
    }

    // ── coerce: missing argument — optional without default ──────────────────────

    @Test
    void coerce_optionalNoDefault_optionalInt_returnsEmpty() {
        Param<OptionalInt> p = Param.of(OptionalInt.class, "n", "Number", false, "");
        OptionalInt result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals(OptionalInt.empty(), result);
    }

    @Test
    void coerce_optionalNoDefault_optionalLong_returnsEmpty() {
        Param<OptionalLong> p = Param.of(OptionalLong.class, "ts", "Timestamp", false, "");
        OptionalLong result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals(OptionalLong.empty(), result);
    }

    @Test
    void coerce_optionalNoDefault_optionalDouble_returnsEmpty() {
        Param<OptionalDouble> p = Param.of(OptionalDouble.class, "ratio", "Ratio", false, "");
        OptionalDouble result = ParamCoercion.coerce(Map.of(), p, MAPPER);
        assertEquals(OptionalDouble.empty(), result);
    }

    // ── coerce: conversion failure ───────────────────────────────────────────────

    @Test
    void coerce_incompatibleType_throwsWithParamName() {
        Param<Integer> p = Param.of(Integer.class, "count", "Count");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ParamCoercion.coerce(Map.of("count", "not_a_number"), p, MAPPER));
        assertTrue(ex.getMessage().contains("count"));
    }

    // ── Test helper types ────────────────────────────────────────────────────────

    enum TestMode {
        FAST, SLOW, NORMAL
    }
}
