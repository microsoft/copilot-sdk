/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.generated.rpc.PermissionDecisionContext;
import com.microsoft.copilot.generated.rpc.PermissionDecisionOutcome;
import com.microsoft.copilot.generated.rpc.PermissionDecisionSource;
import com.microsoft.copilot.generated.rpc.PermissionDecisionSurface;
import com.microsoft.copilot.generated.rpc.SessionPermissionsHandlePendingPermissionRequestParams;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link PermissionRequestResult} carries an optional
 * {@link PermissionDecisionContext} as a sibling of {@code result} — never
 * nested inside the serialized result — when the SDK forwards a permission
 * response to the runtime.
 */
class PermissionRequestResultDecisionContextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PermissionDecisionContext sampleContext() {
        return PermissionDecisionContext.builder().outcome(PermissionDecisionOutcome.AUTO_APPROVED)
                .source(PermissionDecisionSource.HOST_POLICY).surface(PermissionDecisionSurface.SDK)
                .responseCapability(null).userFeedback(null).build();
    }

    @Test
    void setDecisionContextForwardsContextAsSiblingOfResult() throws Exception {
        var result = PermissionRequestResult.approveOnce().setDecisionContext(sampleContext());
        var params = SessionPermissionsHandlePendingPermissionRequestParams.builder().sessionId("session-1")
                .requestId("req-1").result(result).decisionContext(result.getDecisionContext()).build();

        JsonNode json = MAPPER.valueToTree(params);

        assertTrue(json.has("decisionContext"), "decisionContext must be a top-level sibling of result");
        assertEquals("host_policy", json.get("decisionContext").get("source").asText());
        assertEquals("auto_approved", json.get("decisionContext").get("outcome").asText());
        assertEquals("sdk", json.get("decisionContext").get("surface").asText());
        assertFalse(json.get("result").has("decisionContext"), "decisionContext must NOT be nested inside result");
    }

    @Test
    void serializingResultWithContextDoesNotEmitContextInsideResult() throws Exception {
        var result = PermissionRequestResult.approveOnce().setDecisionContext(sampleContext());

        JsonNode resultJson = MAPPER.valueToTree(result);

        assertFalse(resultJson.has("decisionContext"),
                "@JsonIgnore must keep decisionContext out of the serialized result");
        assertEquals(PermissionRequestResultKind.APPROVED.getValue(), resultJson.get("kind").asText());
    }
}
