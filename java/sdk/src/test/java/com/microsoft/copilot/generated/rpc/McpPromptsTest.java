/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.generated.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generated serialization supplements TypeScript's real MCP tests.
 */
class McpPromptsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void promptResultsPreserveOpaqueContentAndOptionalFields() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        Path fixture = null;
        while (directory != null) {
            Path candidate = directory.resolve("test/harness/mcp-prompt-fixtures.json");
            if (Files.exists(candidate)) {
                fixture = candidate;
                break;
            }
            directory = directory.getParent();
        }
        assertNotNull(fixture, "Cannot find shared MCP prompt fixture");
        JsonNode fixtures = mapper.readTree(fixture.toFile());
        for (String page : List.of("firstPage", "secondPage")) {
            var result = mapper.treeToValue(fixtures.get(page), SessionMcpPromptsListResult.class);
            assertEquals(fixtures.get(page), mapper.valueToTree(result));
        }
        var result = mapper.treeToValue(fixtures.get("richPrompt"), SessionMcpPromptsGetResult.class);
        assertEquals(fixtures.get("richPrompt"), mapper.valueToTree(result));
    }
}
