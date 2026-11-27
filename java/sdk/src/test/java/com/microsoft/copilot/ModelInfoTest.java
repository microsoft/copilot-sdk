/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.generated.rpc.Model;

/**
 * Tests for the wire shape of the optional native model provider.
 */
class ModelInfoTest {

    @Test
    void modelInfoPreservesOptionalNativeProvider() throws Exception {
        var mapper = new ObjectMapper();
        for (var provider : List.of("{\"id\":\"copilot\",\"kind\":\"copilot\",\"label\":\"GitHub Copilot\"}",
                "{\"id\":\"loki:opaque\",\"kind\":\"loki\",\"label\":\"Microsoft 365 Copilot\"}")) {
            var model = mapper
                    .readValue("{\"id\":\"shared-model\",\"name\":\"Shared Model\",\"capabilities\":{},\"provider\":"
                            + provider + "}", Model.class);
            assertEquals(mapper.readTree(provider), mapper.valueToTree(model.provider()));
            assertEquals(mapper.readTree(provider), mapper.valueToTree(model).get("provider"));
        }
        var unattributed = mapper.readValue("{\"id\":\"configured/model\"}", Model.class);
        assertNull(unattributed.provider());
        assertFalse(mapper.valueToTree(unattributed).has("provider"));
    }
}
