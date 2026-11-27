/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.copilot;

import com.microsoft.copilot.rpc.SessionToolOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.copilot.rpc.ImageGenerationConfig;
import com.microsoft.copilot.rpc.ResumeSessionOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;

class ImageGenerationConfigTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createAndResumeForwardExplicitOptIn(boolean enabled) {
        var imageGeneration = new ImageGenerationConfig().setEnabled(enabled);
        var createConfig = new CreateSessionOptions()
                .setTools(new SessionToolOptions().setImageGeneration(imageGeneration)).clone();
        var resumeConfig = new ResumeSessionOptions()
                .setTools(new SessionToolOptions().setImageGeneration(imageGeneration)).clone();
        assertSame(imageGeneration, createConfig.getTools().getImageGeneration());
        assertSame(imageGeneration, resumeConfig.getTools().getImageGeneration());
        var create = SessionRequestBuilder.buildCreateRequest(createConfig, "image-test");
        var resume = SessionRequestBuilder.buildResumeRequest("image-test", resumeConfig);
        for (Object request : new Object[]{create, resume}) {
            JsonNode json = JsonRpcClient.getObjectMapper().valueToTree(request);
            assertEquals(enabled, json.path("tools").get("imageGeneration").get("enabled").booleanValue());
            assertEquals(1, json.path("tools").get("imageGeneration").size());
        }
    }

    @Test
    void createAndResumeOmitUnsetOptIn() {
        var create = SessionRequestBuilder.buildCreateRequest(new CreateSessionOptions(), "image-test");
        var resume = SessionRequestBuilder.buildResumeRequest("image-test", new ResumeSessionOptions());
        for (Object request : new Object[]{create, resume}) {
            JsonNode json = JsonRpcClient.getObjectMapper().valueToTree(request);
            assertFalse(json.path("tools").has("imageGeneration"));
        }
        JsonNode empty = JsonRpcClient.getObjectMapper().valueToTree(new ImageGenerationConfig());
        assertEquals(0, empty.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createAndResumeForwardWatermarkWithoutEnablingImages(boolean watermark) {
        var imageGeneration = new ImageGenerationConfig().setWatermark(watermark);
        assertEquals(watermark, imageGeneration.getWatermark());
        var create = SessionRequestBuilder.buildCreateRequest(new CreateSessionOptions()
                .setTools(new SessionToolOptions().setImageGeneration(imageGeneration)).clone(), "image-test");
        var resume = SessionRequestBuilder.buildResumeRequest("image-test", new ResumeSessionOptions()
                .setTools(new SessionToolOptions().setImageGeneration(imageGeneration)).clone());
        for (Object request : new Object[]{create, resume}) {
            JsonNode json = JsonRpcClient.getObjectMapper().valueToTree(request);
            assertEquals(watermark, json.path("tools").get("imageGeneration").get("watermark").booleanValue());
            assertEquals(1, json.path("tools").get("imageGeneration").size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createAndResumeForwardSaveToDiskWithoutEnablingImages(boolean saveToDisk) {
        var imageGeneration = new ImageGenerationConfig().setSaveToDisk(saveToDisk);
        assertEquals(saveToDisk, imageGeneration.getSaveToDisk());
        var create = SessionRequestBuilder.buildCreateRequest(new CreateSessionOptions()
                .setTools(new SessionToolOptions().setImageGeneration(imageGeneration)).clone(), "image-test");
        var resume = SessionRequestBuilder.buildResumeRequest("image-test", new ResumeSessionOptions()
                .setTools(new SessionToolOptions().setImageGeneration(imageGeneration)).clone());
        for (Object request : new Object[]{create, resume}) {
            JsonNode json = JsonRpcClient.getObjectMapper().valueToTree(request);
            assertEquals(saveToDisk, json.path("tools").get("imageGeneration").get("saveToDisk").booleanValue());
            assertEquals(1, json.path("tools").get("imageGeneration").size());
        }
    }
}
