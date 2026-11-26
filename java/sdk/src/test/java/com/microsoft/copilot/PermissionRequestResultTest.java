/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.generated.PermissionRequestedEvent;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.PermissionInvocation;
import com.microsoft.copilot.rpc.PermissionRequest;
import com.microsoft.copilot.rpc.UrlPermissionPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tests for {@link PermissionRequestResult} factory methods and feedback field.
 */
public class PermissionRequestResultTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    @Test
    void testApproveOnce() {
        var result = PermissionRequestResult.approveOnce();
        assertEquals("approve-once", result.getKind());
        assertNull(result.getFeedback());
    }

    @Test
    void testApproveReadOnlyForSession() throws Exception {
        var result = PermissionRequestResult.approveReadOnlyForSession(List.of("/outside/reference"));
        var json = MAPPER.readTree(MAPPER.writeValueAsString(result));

        assertEquals("approve-read-only-for-session", result.getKind());
        assertEquals(List.of("/outside/reference"), result.getDirectories());
        assertEquals("approve-read-only-for-session", json.get("kind").asText());
        assertEquals("/outside/reference", json.get("directories").get(0).asText());
        assertFalse(json.has("locationKey"));
    }

    @Test
    void testRejectWithFeedback() {
        var result = PermissionRequestResult.reject("Not allowed");
        assertEquals("reject", result.getKind());
        assertEquals("Not allowed", result.getFeedback());
    }

    @Test
    void testRejectWithoutFeedback() {
        var result = PermissionRequestResult.reject(null);
        assertEquals("reject", result.getKind());
        assertNull(result.getFeedback());
    }

    @Test
    void testUserNotAvailable() {
        var result = PermissionRequestResult.userNotAvailable();
        assertEquals("user-not-available", result.getKind());
        assertNull(result.getFeedback());
    }

    @Test
    void testNoResult() {
        var result = PermissionRequestResult.noResult();
        assertEquals("no-result", result.getKind());
        assertNull(result.getFeedback());
    }

    @Test
    void testFeedbackSerialized() throws Exception {
        var result = PermissionRequestResult.reject("Unsafe operation");
        var json = MAPPER.writeValueAsString(result);
        assertTrue(json.contains("\"feedback\":\"Unsafe operation\""));
        assertTrue(json.contains("\"kind\":\"reject\""));
    }

    @Test
    void testFeedbackNotSerializedWhenNull() throws Exception {
        var result = PermissionRequestResult.approveOnce();
        var json = MAPPER.writeValueAsString(result);
        assertFalse(json.contains("feedback"));
    }

    @Test
    void testPermissionRequestExposesManagedApprovalRequired() throws Exception {
        var request = MAPPER.readValue("""
                {
                    "kind": "read",
                    "path": "/workspace/file.txt",
                    "managedApprovalRequired": true,
                    "readOnlyDirectories": ["/outside/reference"]
                }
                """, PermissionRequest.class);

        assertTrue(request.getManagedApprovalRequired());
        assertEquals(List.of("/outside/reference"), request.getReadOnlyDirectories());
    }

    @Test
    void testPermissionRequestExposesServerDisplayName() throws Exception {
        var request = MAPPER.readValue("""
                {
                    "kind": "mcp",
                    "serverName": "github-copilot-connector-1",
                    "serverDisplayName": "Work IQ"
                }
                """, PermissionRequest.class);

        assertEquals("Work IQ", request.getServerDisplayName());
        assertEquals("github-copilot-connector-1", request.getExtensionData().get("serverName"));
    }

    @Test
    void testMalformedManagedApprovalRequiredFailsClosed() throws Exception {
        var request = MAPPER.readValue("""
                {
                    "kind": "read",
                    "managedApprovalRequired": 0
                }
                """, PermissionRequest.class);

        assertTrue(request.getManagedApprovalRequired());
        var result = PermissionHandler.APPROVE_ALL.handle(request, new PermissionInvocation()).join();
        assertEquals("no-result", result.getKind());
    }

    @Test
    void testManagedApprovalRequiredPreservesFalse() throws Exception {
        var request = MAPPER.readValue("""
                {
                    "kind": "read",
                    "managedApprovalRequired": false
                }
                """, PermissionRequest.class);

        assertFalse(request.getManagedApprovalRequired());
        var result = PermissionHandler.APPROVE_ALL.handle(request, new PermissionInvocation()).join();
        assertEquals("approve-once", result.getKind());
    }

    @Test
    void testPermissionEventValueConvertsToTypedRequest() {
        var event = MAPPER
                .convertValue(
                        java.util.Map.of("type", "permission.requested", "data",
                                java.util.Map.of("requestId", "permission-1", "permissionRequest", java.util.Map.of(
                                        "kind", "url", "managedApprovalRequired", true, "url", "https://example.com"))),
                        PermissionRequestedEvent.class);
        var request = PermissionRequest.fromJsonValue(event.getData().permissionRequest());

        assertTrue(request.getManagedApprovalRequired());
    }

    @Test
    void testApproveAllFailsWhenManagedSettingsEnabled() {
        var request = new PermissionRequest();
        request.setKind("read");
        request.setManagedApprovalRequired(true);

        var invocation = new PermissionInvocation().setManagedSettingsEnabled(true);
        var error = assertThrows(java.util.concurrent.CompletionException.class,
                () -> PermissionHandler.APPROVE_ALL.handle(request, invocation).join());

        assertTrue(error.getCause() instanceof IllegalStateException);
    }

    @Test
    void testApproveAllApprovesOrdinaryRequest() {
        var request = new PermissionRequest();
        request.setKind("read");

        var result = PermissionHandler.APPROVE_ALL.handle(request, new PermissionInvocation()).join();

        assertEquals("approve-once", result.getKind());
    }

    @Test
    void testApproveAllLeavesManagedRequestPendingWhenSessionFlagIsAbsent() {
        var request = new PermissionRequest();
        request.setKind("read");
        request.setManagedApprovalRequired(true);

        var result = PermissionHandler.APPROVE_ALL.handle(request, new PermissionInvocation()).join();

        assertEquals("no-result", result.getKind());
    }

    @Test
    void testApproveUrlForSessionSerializesDomain() throws Exception {
        var result = PermissionRequestResult.approveUrlForSession("https://*.example.com");
        var json = MAPPER.readTree(MAPPER.writeValueAsString(result));

        assertEquals("approve-for-session", json.get("kind").asText());
        assertEquals("https://*.example.com", json.get("domain").asText());
        assertEquals(2, json.size(), json::toString);
    }

    @Test
    void testApproveUrlPermanentlySerializesDomain() throws Exception {
        var result = PermissionRequestResult.approveUrlPermanently("https://api.example.com");
        var json = MAPPER.readTree(MAPPER.writeValueAsString(result));

        assertEquals("approve-permanently", json.get("kind").asText());
        assertEquals("https://api.example.com", json.get("domain").asText());
        assertEquals(2, json.size(), json::toString);
    }

    @Test
    void testUrlApprovalsRequireDomain() {
        assertThrows(NullPointerException.class, () -> PermissionRequestResult.approveUrlForSession(null));
        assertThrows(NullPointerException.class, () -> PermissionRequestResult.approveUrlPermanently(null));
    }

    @Test
    void testUrlPromptExposesTypedFields() {
        var prompt = PermissionRequest.fromJsonValue(java.util.Map.of("kind", "url", "toolCallId", "call-1", "url",
                "https://api.example.com/a", "intention", "Fetch docs", "redirectedFrom", "https://example.com/start",
                "parentDomainPattern", "https://*.example.com", "requestSandboxBypass", true,
                "requestSandboxBypassReason", "Blocked by policy"));

        var url = UrlPermissionPrompt.from(prompt);

        assertNotNull(url);
        assertEquals("https://api.example.com/a", url.getUrl());
        assertEquals("Fetch docs", url.getIntention());
        assertEquals("https://example.com/start", url.getRedirectedFrom());
        assertEquals("https://*.example.com", url.getParentDomainPattern());
        assertEquals(Boolean.TRUE, url.getRequestSandboxBypass());
        assertEquals("Blocked by policy", url.getRequestSandboxBypassReason());
        // Existing readers of the untyped map keep working.
        assertEquals("https://api.example.com/a", prompt.getExtensionData().get("url"));
    }

    @Test
    void testUrlPromptOptionalFieldsAreNullWhenAbsent() {
        var url = UrlPermissionPrompt
                .from(PermissionRequest.fromJsonValue(java.util.Map.of("kind", "url", "url", "http://10.0.0.1/")));

        assertNotNull(url);
        assertEquals("http://10.0.0.1/", url.getUrl());
        assertNull(url.getIntention());
        assertNull(url.getRedirectedFrom());
        assertNull(url.getParentDomainPattern());
        assertNull(url.getRequestSandboxBypass());
        assertNull(url.getRequestSandboxBypassReason());
    }

    @Test
    void testUrlPromptWrongTypesReadAsNull() {
        var url = UrlPermissionPrompt.from(PermissionRequest.fromJsonValue(java.util.Map.of("kind", "url", "url", 42,
                "parentDomainPattern", java.util.List.of("https://*.example.com"), "requestSandboxBypass", "true")));

        assertNotNull(url);
        assertNull(url.getUrl());
        assertNull(url.getParentDomainPattern());
        assertNull(url.getRequestSandboxBypass());
    }

    @Test
    void testUrlPromptIsNullForOtherKinds() {
        assertNull(UrlPermissionPrompt.from(null));
        assertNull(UrlPermissionPrompt.from(
                PermissionRequest.fromJsonValue(java.util.Map.of("kind", "read", "url", "https://api.example.com/a"))));
        assertNull(UrlPermissionPrompt.from(new PermissionRequest()));
    }
}
