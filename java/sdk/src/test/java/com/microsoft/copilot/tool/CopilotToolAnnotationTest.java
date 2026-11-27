/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.rpc.ToolDefer;

/**
 * Unit tests for {@link CopilotTool} and {@link CopilotToolParam} annotations.
 */
public class CopilotToolAnnotationTest {

    // --- @CopilotTool attribute verification ---

    @Test
    void copilotToolHasRuntimeRetention() {
        Retention retention = CopilotTool.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void copilotToolTargetsMethod() {
        Target target = CopilotTool.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    @Test
    void copilotToolDeclaresCopilotExperimentalInClassFile() throws Exception {
        String classFileResourcePath = "/" + CopilotTool.class.getName().replace('.', '/') + ".class";
        try (InputStream classFile = CopilotTool.class.getResourceAsStream(classFileResourcePath)) {
            assertNotNull(classFile, "CopilotTool class file must be readable as a resource");
            String classFileText = new String(classFile.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(classFileText.contains("com/microsoft/copilot/CopilotExperimental"));
        }
    }

    @Test
    void copilotToolDefaultValues() throws Exception {
        Method nameMethod = CopilotTool.class.getDeclaredMethod("name");
        assertEquals("", nameMethod.getDefaultValue());

        Method overridesMethod = CopilotTool.class.getDeclaredMethod("overridesBuiltInTool");
        assertEquals(false, overridesMethod.getDefaultValue());

        Method skipMethod = CopilotTool.class.getDeclaredMethod("skipPermission");
        assertEquals(false, skipMethod.getDefaultValue());

        Method deferMethod = CopilotTool.class.getDeclaredMethod("defer");
        assertEquals(ToolDefer.NONE, deferMethod.getDefaultValue());
    }

    // --- @CopilotToolParam attribute verification ---

    @Test
    void paramHasRuntimeRetention() {
        Retention retention = CopilotToolParam.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void paramTargetsParameter() {
        Target target = CopilotToolParam.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.PARAMETER}, target.value());
    }

    @Test
    void paramDefaultValues() throws Exception {
        Method valueMethod = CopilotToolParam.class.getDeclaredMethod("value");
        assertEquals("", valueMethod.getDefaultValue());

        Method nameMethod = CopilotToolParam.class.getDeclaredMethod("name");
        assertEquals("", nameMethod.getDefaultValue());

        Method requiredMethod = CopilotToolParam.class.getDeclaredMethod("required");
        assertEquals(true, requiredMethod.getDefaultValue());

        Method defaultValueMethod = CopilotToolParam.class.getDeclaredMethod("defaultValue");
        assertEquals("", defaultValueMethod.getDefaultValue());
    }

    // --- Applicability test ---

    @SuppressWarnings("unused")
    static class SampleToolHolder {

        @CopilotTool(value = "Get weather for a location", name = "get_weather", defer = ToolDefer.AUTO)
        public CompletableFuture<String> getWeather(
                @CopilotToolParam(value = "City name", required = true) String location,
                @CopilotToolParam(value = "Temperature unit", required = false, defaultValue = "celsius") String unit) {
            return CompletableFuture.completedFuture("Sunny in " + location);
        }
    }
}
