/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.generated.rpc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.AllowCopilotExperimental;

@AllowCopilotExperimental
class FetchErrorSerializationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void responseModesMatchRequestModesAndRoundTrip() throws Exception {
        for (var mode : FetchResponseMode.values()) {
            var json = MAPPER.createObjectNode();
            json.put("responseMode", mode.getValue());
            json.put("url", "https://example.com");
            json.put("status", 200);
            json.put("statusText", "OK");
            json.putArray("headers");
            if (mode == FetchResponseMode.TEXT) {
                json.put("bodyText", "hello");
                json.put("endedAtUnverifiedEof", false);
            } else if (mode == FetchResponseMode.FILE) {
                json.put("path", "response.txt");
                json.put("sizeBytes", 5);
                json.put("endedAtUnverifiedEof", false);
            }
            var result = MAPPER.treeToValue(json, FetchSendResult.class);
            assertTrue(result.getResponseMode().equals(mode), mode.getValue());
            assertEquals(json, MAPPER.readTree(MAPPER.writeValueAsString(result)));
        }
    }
}
