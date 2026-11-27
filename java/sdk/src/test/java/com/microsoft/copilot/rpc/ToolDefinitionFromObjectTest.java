/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.rpc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.AllowCopilotExperimental;
import com.microsoft.copilot.rpc.fixtures.SimpleTools;

/**
 * Production-path error tests for {@link ToolDefinition#fromObject(Object)} and
 * {@link ToolDefinition#fromClass(Class)}. Generated-companion loading and
 * invocation are covered against real processor output in
 * {@code CopilotToolProcessorTest}.
 */
@AllowCopilotExperimental
class ToolDefinitionFromObjectTest {

    @Test
    void fromObject_throwsOnMissingMetaClass() {
        // A class that was never processed by CopilotToolProcessor
        var ex = assertThrows(IllegalStateException.class, () -> ToolDefinition.fromObject("a plain String"));
        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("CopilotToolProcessor"));
    }

    @Test
    void fromClass_throwsOnInstanceMethods() {
        // SimpleTools has instance (non-static) @CopilotTool methods
        var ex = assertThrows(IllegalArgumentException.class, () -> ToolDefinition.fromClass(SimpleTools.class));
        assertTrue(ex.getMessage().contains("fromClass()"));
        assertTrue(ex.getMessage().contains("static"));
        assertTrue(ex.getMessage().contains("fromObject"));
    }
}
