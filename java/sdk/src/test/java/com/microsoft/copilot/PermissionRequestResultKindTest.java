/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.rpc.PermissionRequestResultKind;

/**
 * Unit tests for {@link PermissionRequestResultKind}.
 * <p>
 * Covers well-known kind values, equality, hash code, serialization, and
 * backward-compatible {@link PermissionRequestResult} integration.
 */
public class PermissionRequestResultKindTest {

    @Test
    void wellKnownKinds_haveExpectedValues() {
        assertEquals("approve-once", PermissionRequestResultKind.APPROVED.getValue());
        assertEquals("approve-read-only-for-session",
                PermissionRequestResultKind.APPROVE_READ_ONLY_FOR_SESSION.getValue());
        assertEquals("reject", PermissionRequestResultKind.REJECTED.getValue());
        assertEquals("user-not-available", PermissionRequestResultKind.USER_NOT_AVAILABLE.getValue());
        assertEquals("no-result", PermissionRequestResultKind.NO_RESULT.getValue());
    }

    @Test
    void equals_isCaseInsensitive() {
        var upper = new PermissionRequestResultKind("APPROVE-ONCE");
        assertEquals(PermissionRequestResultKind.APPROVED, upper);
    }

    @Test
    void hashCode_agreesWithEquals_forNonAsciiCaseVariants() {
        assertEqualKindsHashAlike("\u0130", "i"); // capital I with dot above
        assertEqualKindsHashAlike("\u0131", "I"); // dotless small i
        assertEqualKindsHashAlike("\u03C2", "\u03A3"); // final sigma, capital sigma
        assertEqualKindsHashAlike("\u03C2", "\u03C3"); // final sigma, small sigma
        assertEqualKindsHashAlike("\uD801\uDC00", "\uD801\uDC28"); // Deseret, outside the BMP
    }

    private static void assertEqualKindsHashAlike(String a, String b) {
        var left = new PermissionRequestResultKind("approve-" + a);
        var right = new PermissionRequestResultKind("approve-" + b);
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode(), "equal kinds must hash alike: " + left + " vs " + right);
    }

    @Test
    void constructor_nullValue_treatedAsEmpty() {
        var kind = new PermissionRequestResultKind(null);
        assertEquals("", kind.getValue());
        assertEquals("", kind.toString());
    }

    @Test
    void jsonSerialize_writesStringValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var result = new PermissionRequestResult().setKind(PermissionRequestResultKind.APPROVED);
        String json = mapper.writeValueAsString(result);
        assertTrue(json.contains("\"kind\":\"approve-once\""), "Expected kind to be serialized as string: " + json);
    }

    @Test
    void jsonDeserialize_readsStringValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"kind\":\"reject\"}";
        var result = mapper.readValue(json, PermissionRequestResult.class);
        assertEquals("reject", result.getKind());
    }
}
