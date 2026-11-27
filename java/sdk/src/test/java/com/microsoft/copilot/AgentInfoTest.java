/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.rpc.AgentInfo;

/**
 * Wire tests for {@link AgentInfo} hooks and plugin ownership.
 */
class AgentInfoTest {

    @Test
    void hooksGetterSetterAndJsonRoundTrip() throws Exception {
        Map<String, List<Map<String, Object>>> hooks = Map.of("preToolUse",
                List.of(Map.of("type", "command", "bash", "echo scoped-hook", "timeoutSec", 10)));
        var agent = new AgentInfo().setName("coder").setHooks(hooks);
        assertEquals(hooks, agent.getHooks());

        var mapper = JsonRpcClient.getObjectMapper();
        var json = mapper.valueToTree(agent);
        assertEquals(mapper.valueToTree(hooks), json.get("hooks"));
        var restored = mapper.treeToValue(json, AgentInfo.class);
        assertEquals(hooks, restored.getHooks());
        assertNull(mapper.readValue("{\"name\":\"plain-agent\"}", AgentInfo.class).getHooks());
    }

    @Test
    void pluginOwnershipJsonRoundTrip() throws Exception {
        var mapper = JsonRpcClient.getObjectMapper();
        var agent = mapper.readValue("""
                {"name":"coder","pluginId":"mp:catalog::tools"}
                """, AgentInfo.class);
        assertEquals("mp:catalog::tools", agent.getPluginId());
        assertEquals("mp:catalog::tools", mapper.valueToTree(agent).get("pluginId").asText());
        assertNull(mapper.readValue("{\"name\":\"plain-agent\"}", AgentInfo.class).getPluginId());
        assertSame(agent, agent.setPluginId("builtin:tools"));
        assertEquals("builtin:tools", mapper.valueToTree(agent).get("pluginId").asText());
    }
}
