/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.generated.UserMessageDelivery;
import com.microsoft.copilot.generated.UserMessageEvent.UserMessageEventData;

class ClientTurnIdCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void userMessageConstructorWithClientTurnIdKeepsArgumentMeaning() {
        var data = new UserMessageEventData(null, "hi", null, null, "message-1", "host-turn-1", null, null, null, null,
                null, "user", null, UserMessageDelivery.STEERING, null, null, null, "turn-1", null);

        assertEquals("message-1", data.messageId());
        assertEquals("host-turn-1", data.clientTurnId());
        assertEquals(UserMessageDelivery.STEERING, data.delivery());
        assertEquals("turn-1", data.turnId());
        assertNull(data.hasSubmittedTask());
        assertFalse(MAPPER.valueToTree(data).has("hasSubmittedTask"));
        assertNull(data.agentAuthored());
        assertFalse(MAPPER.valueToTree(data).has("agentAuthored"));
        assertNull(data.responsesTextFormat());
        assertFalse(MAPPER.valueToTree(data).has("responsesTextFormat"));
    }
}
