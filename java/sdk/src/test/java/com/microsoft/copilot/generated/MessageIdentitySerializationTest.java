/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.generated.rpc.SessionSendParams;

@com.microsoft.copilot.AllowCopilotExperimental
class MessageIdentitySerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testPublishedSendSourceAccessorsPreserveWireProvenance() throws Exception {
        var request = SessionSendParams.builder().prompt("hello").source("command-review").build();

        assertEquals("command-review", request.source());
        assertEquals(MAPPER.readTree("""
                {"prompt": "hello", "source": "command-review"}
                """), MAPPER.valueToTree(request));
        assertFalse(java.util.Arrays.stream(SessionSendParams.class.getMethods())
                .anyMatch(method -> method.getName().equals("getSendAdmissionId")
                        || method.getName().equals("setSendAdmissionId")
                        || method.getName().equals("sendAdmissionId")));
    }
}
