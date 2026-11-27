/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.generated.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.AllowCopilotExperimental;

@AllowCopilotExperimental
class RequiredNullableSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RULE = """
            {
              "kind": "read",
              "argument": null
            }
            """;

    @Test
    void requiredNullableArgumentIsExplicit() throws Exception {
        var rule = MAPPER.readValue(RULE, PermissionRule.class);
        var serialized = MAPPER.readTree(MAPPER.writeValueAsString(rule));

        assertTrue(serialized.has("argument"), serialized.toString());
        assertTrue(serialized.get("argument").isNull());
        assertEquals(rule, MAPPER.treeToValue(serialized, PermissionRule.class));
    }

    @Test
    void nestedRuleRetainsRequiredNullWithoutChangingOptionalRequestFields() throws Exception {
        var rule = MAPPER.readValue(RULE, PermissionRule.class);
        var request = SessionPermissionsModifyRulesParams.builder().sessionId("fixture-session")
                .scope(PermissionsModifyRulesScope.SESSION).add(List.of(rule)).build();
        var serialized = MAPPER.readTree(MAPPER.writeValueAsString(request));

        assertTrue(serialized.get("add").get(0).has("argument"), serialized.toString());
        assertTrue(serialized.get("add").get(0).get("argument").isNull());
        assertFalse(serialized.has("remove"));
        assertFalse(serialized.has("removeAll"));
        assertEquals("fixture-session", serialized.get("sessionId").asText());
        assertEquals("session", serialized.get("scope").asText());
    }
}
