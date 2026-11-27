/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import com.microsoft.copilot.rpc.SessionStateOptions;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.microsoft.copilot.generated.rpc.MemoryMode;
import com.microsoft.copilot.rpc.CapiSessionOptions;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.CustomAgentConfig;
import com.microsoft.copilot.rpc.InfiniteSessionConfig;
import com.microsoft.copilot.rpc.InputOptions;
import com.microsoft.copilot.rpc.MemoryConfiguration;
import com.microsoft.copilot.rpc.ModelCapabilitiesOverride;
import com.microsoft.copilot.rpc.ProviderConfig;
import com.microsoft.copilot.rpc.ResumeSessionOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;
import com.microsoft.copilot.rpc.SessionUiCapabilities;
import com.microsoft.copilot.rpc.TelemetryConfig;
import com.microsoft.copilot.rpc.UserInputRequest;

/**
 * Verifies that public DTO classes in the {@code com.microsoft.copilot.rpc}
 * package are annotated with {@code @JsonInclude(JsonInclude.Include.NON_NULL)}
 * so that null-valued fields are omitted during JSON serialization.
 */
class JsonIncludeNonNullTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Annotation presence checks ---

    @ParameterizedTest
    @ValueSource(classes = {CopilotClientOptions.class, CreateSessionOptions.class, ResumeSessionOptions.class,
            InfiniteSessionConfig.class, MemoryConfiguration.class, ModelCapabilitiesOverride.class,
            ProviderConfig.class, CapiSessionOptions.class, CustomAgentConfig.class})
    void wireOptionTypesOmitNullFields(Class<?> clazz) {
        assertHasNonNullInclude(clazz);
    }

    // --- Serialization tests: null fields are omitted ---

    @Test
    void userInputRequestOmitsNullFieldsInJson() throws JsonProcessingException {
        var req = new UserInputRequest();
        String json = MAPPER.writeValueAsString(req);
        assertFalse(json.contains("null"), "UserInputRequest with no fields set should not contain 'null' values");
    }

    @Test
    void inputOptionsSerializesOnlySetFields() throws JsonProcessingException {
        assertEquals("{}", MAPPER.writeValueAsString(new InputOptions()));
        var opts = new InputOptions();
        opts.setMinLength(5);
        opts.setMaxLength(100);
        assertEquals(MAPPER.readTree("{\"minLength\":5,\"maxLength\":100}"), MAPPER.valueToTree(opts));
    }

    @Test
    void telemetryConfigSerializesOnlySetFields() throws JsonProcessingException {
        assertEquals("{}", MAPPER.writeValueAsString(new TelemetryConfig()));
        var config = new TelemetryConfig();
        config.setOtlpEndpoint("http://localhost:4318");
        assertEquals(MAPPER.readTree("{\"otlpEndpoint\":\"http://localhost:4318\"}"), MAPPER.valueToTree(config));
    }

    @Test
    void sessionUiCapabilitiesSerializesOnlySetFields() throws JsonProcessingException {
        assertEquals("{}", MAPPER.writeValueAsString(new SessionUiCapabilities()));
        var caps = new SessionUiCapabilities();
        caps.setElicitation(true);
        assertEquals(MAPPER.readTree("{\"elicitation\":true}"), MAPPER.valueToTree(caps));
    }

    @Test
    void memoryConfigurationSerializesEnabled() throws JsonProcessingException {
        var memory = new MemoryConfiguration().setEnabled(true);
        String json = MAPPER.writeValueAsString(memory);
        assertEquals("{\"enabled\":true}", json, "MemoryConfiguration should serialize the required enabled field");

        var disabled = new MemoryConfiguration().setEnabled(false);
        assertEquals("{\"enabled\":false}", MAPPER.writeValueAsString(disabled),
                "MemoryConfiguration should serialize enabled even when false");
    }

    @ParameterizedTest
    @EnumSource(MemoryMode.class)
    @AllowCopilotExperimental
    void memoryModeRoundTripsAndForwardsToSessionConfigs(MemoryMode mode) throws JsonProcessingException {
        var memory = new MemoryConfiguration().setEnabled(true).setMode(mode);
        var json = MAPPER.writeValueAsString(memory);
        assertEquals(mode.getValue(), MAPPER.readTree(json).get("mode").asText());
        assertEquals(mode, MAPPER.readValue(json, MemoryConfiguration.class).getMode());
        assertEquals(mode.getValue(),
                MAPPER.valueToTree(new CreateSessionOptions().setState(new SessionStateOptions().setMemory(memory)))
                        .path("state").path("memory").path("mode").asText());
        assertEquals(mode.getValue(),
                MAPPER.valueToTree(new ResumeSessionOptions().setState(new SessionStateOptions().setMemory(memory)))
                        .path("state").path("memory").path("mode").asText());
        memory.setMode(null);
        assertFalse(MAPPER.valueToTree(memory).has("mode"));
    }

    @Test
    void sessionConfigOmitsMemoryWhenUnset() throws JsonProcessingException {
        var config = new CreateSessionOptions();
        String json = MAPPER.writeValueAsString(config);
        assertFalse(json.contains("\"memory\""), "Unset memory should be omitted from CreateSessionOptions JSON");
    }

    private void assertHasNonNullInclude(Class<?> clazz) {
        JsonInclude annotation = clazz.getAnnotation(JsonInclude.class);
        assertNotNull(annotation, clazz.getSimpleName() + " should be annotated with @JsonInclude");
        assertEquals(JsonInclude.Include.NON_NULL, annotation.value(),
                clazz.getSimpleName() + " @JsonInclude should use Include.NON_NULL");
    }

}
