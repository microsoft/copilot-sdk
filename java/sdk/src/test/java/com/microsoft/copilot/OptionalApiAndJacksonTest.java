/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.rpc.InfiniteSessionConfig;
import com.microsoft.copilot.rpc.ModelCapabilitiesOverride;
import com.microsoft.copilot.rpc.CreateSessionOptions;
import org.junit.jupiter.api.Test;

/**
 * Validates Jackson wire names and omission of cleared Optional-backed fields
 * for option classes serialized directly.
 */
class OptionalApiAndJacksonTest {

    private static final ObjectMapper MAPPER = JsonRpcClient.getObjectMapper();

    // ── JSON deserialization into Optional-returning classes ───────────

    @Test
    void jackson_deserializeSupportsWithFields() throws Exception {
        String json = "{\"vision\":true,\"reasoningEffort\":false}";
        var supports = MAPPER.readValue(json, ModelCapabilitiesOverride.Supports.class);
        assertTrue(supports.getVision().get());
        assertFalse(supports.getReasoningEffort().get());
    }

    @Test
    void jackson_deserializeSupportsEmpty() throws Exception {
        String json = "{}";
        var supports = MAPPER.readValue(json, ModelCapabilitiesOverride.Supports.class);
        assertTrue(supports.getVision().isEmpty());
        assertTrue(supports.getReasoningEffort().isEmpty());
    }

    @Test
    void jackson_deserializeLimitsWithFields() throws Exception {
        String json = "{\"max_prompt_tokens\":4096,\"max_output_tokens\":1024,\"max_context_window_tokens\":16384}";
        var limits = MAPPER.readValue(json, ModelCapabilitiesOverride.Limits.class);
        assertEquals(4096, limits.getMaxPromptTokens().getAsInt());
        assertEquals(1024, limits.getMaxOutputTokens().getAsInt());
        assertEquals(16384, limits.getMaxContextWindowTokens().getAsInt());
    }

    @Test
    void jackson_deserializeLimitsEmpty() throws Exception {
        String json = "{}";
        var limits = MAPPER.readValue(json, ModelCapabilitiesOverride.Limits.class);
        assertTrue(limits.getMaxPromptTokens().isEmpty());
        assertTrue(limits.getMaxOutputTokens().isEmpty());
        assertTrue(limits.getMaxContextWindowTokens().isEmpty());
    }

    @Test
    void jackson_deserializeInfiniteSessionConfigWithFields() throws Exception {
        String json = "{\"enabled\":true,\"backgroundCompactionThreshold\":0.7,\"bufferExhaustionThreshold\":0.9}";
        var cfg = MAPPER.readValue(json, InfiniteSessionConfig.class);
        assertTrue(cfg.getEnabled().get());
        assertEquals(0.7, cfg.getBackgroundCompactionThreshold().getAsDouble(), 0.001);
        assertEquals(0.9, cfg.getBufferExhaustionThreshold().getAsDouble(), 0.001);
    }

    @Test
    void jackson_deserializeInfiniteSessionConfigEmpty() throws Exception {
        String json = "{}";
        var cfg = MAPPER.readValue(json, InfiniteSessionConfig.class);
        assertTrue(cfg.getEnabled().isEmpty());
        assertTrue(cfg.getBackgroundCompactionThreshold().isEmpty());
        assertTrue(cfg.getBufferExhaustionThreshold().isEmpty());
    }

    // ── Jackson serialization roundtrip ───────────────────────────────
    //
    // Classes whose fields carry @JsonProperty (InfiniteSessionConfig,
    // ModelCapabilitiesOverride inner classes) are serialized via field
    // access: Jackson writes the field when set and omits it when cleared.
    //
    // Classes without @JsonProperty on fields (CreateSessionOptions,
    // CopilotClientOptions, TelemetryConfig, ProviderConfig) are normally
    // copied to wire DTOs by SessionRequestBuilder. Their scalar getters can
    // still be serialized directly, while @JsonIgnore on Optional-returning
    // getters prevents Jackson from attempting to serialize Optional wrappers.

    @Test
    void jackson_sessionConfigEmbeddingCacheStorageSerialized() throws Exception {
        var cfg = new CreateSessionOptions();
        cfg.setState(cfg.getState().setEmbeddingCacheStorage("persistent"));

        String withField = MAPPER.writeValueAsString(cfg);
        assertTrue(withField.contains("\"embeddingCacheStorage\":\"persistent\""));

        cfg.getState().clearEmbeddingCacheStorage();

        String cleared = MAPPER.writeValueAsString(cfg);
        assertFalse(cleared.contains("embeddingCacheStorage"));
    }

    @Test
    void jackson_infiniteSessionConfigClearedFieldsOmitted() throws Exception {
        var cfg = new InfiniteSessionConfig();
        cfg.setEnabled(true);
        cfg.setBackgroundCompactionThreshold(0.75);
        cfg.setBufferExhaustionThreshold(0.9);

        String withFields = MAPPER.writeValueAsString(cfg);
        assertTrue(withFields.contains("enabled"));
        assertTrue(withFields.contains("backgroundCompactionThreshold"));
        assertTrue(withFields.contains("bufferExhaustionThreshold"));

        cfg.clearEnabled();
        cfg.clearBackgroundCompactionThreshold();
        cfg.clearBufferExhaustionThreshold();

        String cleared = MAPPER.writeValueAsString(cfg);
        assertFalse(cleared.contains("enabled"));
        assertFalse(cleared.contains("backgroundCompactionThreshold"));
        assertFalse(cleared.contains("bufferExhaustionThreshold"));
    }

    @Test
    void jackson_modelCapabilitiesOverrideSupportsClearedFieldsOmitted() throws Exception {
        var supports = new ModelCapabilitiesOverride.Supports();
        supports.setVision(true);
        supports.setReasoningEffort(false);

        String withFields = MAPPER.writeValueAsString(supports);
        assertTrue(withFields.contains("vision"));
        assertTrue(withFields.contains("reasoningEffort"));

        supports.clearVision();
        supports.clearReasoningEffort();

        String cleared = MAPPER.writeValueAsString(supports);
        assertFalse(cleared.contains("vision"));
        assertFalse(cleared.contains("reasoningEffort"));
    }

    @Test
    void jackson_modelCapabilitiesOverrideLimitsClearedFieldsOmitted() throws Exception {
        var limits = new ModelCapabilitiesOverride.Limits();
        limits.setMaxPromptTokens(2048);
        limits.setMaxOutputTokens(512);
        limits.setMaxContextWindowTokens(16384);

        String withFields = MAPPER.writeValueAsString(limits);
        assertTrue(withFields.contains("max_prompt_tokens"));
        assertTrue(withFields.contains("max_output_tokens"));
        assertTrue(withFields.contains("max_context_window_tokens"));

        limits.clearMaxPromptTokens();
        limits.clearMaxOutputTokens();
        limits.clearMaxContextWindowTokens();

        String cleared = MAPPER.writeValueAsString(limits);
        assertFalse(cleared.contains("max_prompt_tokens"));
        assertFalse(cleared.contains("max_output_tokens"));
        assertFalse(cleared.contains("max_context_window_tokens"));
    }
}
