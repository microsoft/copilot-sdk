/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import com.microsoft.copilot.rpc.SessionInteractionOptions;
import com.microsoft.copilot.rpc.SessionPermissionOptions;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.rpc.ElicitationHandler;
import com.microsoft.copilot.rpc.ElicitationResult;
import com.microsoft.copilot.rpc.ElicitationResultAction;
import com.microsoft.copilot.rpc.ElicitationSchema;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.ResumeSessionOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;

/**
 * Unit tests for the Elicitation feature and Session Capabilities.
 *
 * <p>
 * Ported from {@code ElicitationTests.cs} in the reference implementation
 * dotnet SDK.
 * </p>
 */
class ElicitationTest {

    @Test
    void elicitationResultActionValues() {
        assertEquals("accept", ElicitationResultAction.ACCEPT.getValue());
        assertEquals("decline", ElicitationResultAction.DECLINE.getValue());
        assertEquals("cancel", ElicitationResultAction.CANCEL.getValue());
    }

    @Test
    void elicitationSchemaDefaultTypeIsObject() {
        var schema = new ElicitationSchema();

        assertEquals("object", schema.getType());
    }

    @Test
    void sessionConfigOnElicitationRequestIsCloned() {
        ElicitationHandler handler = ctx -> CompletableFuture
                .completedFuture(new ElicitationResult().setAction(ElicitationResultAction.ACCEPT));

        var config = new CreateSessionOptions()
                .setPermissions(new SessionPermissionOptions().setOnRequest(PermissionHandler.APPROVE_ALL))
                .setInteraction(new SessionInteractionOptions().setOnElicitationRequest(handler));

        var clone = config.clone();

        // Handler reference is shared (not deep-cloned), but the field is copied
        assertNotNull(clone.getInteraction().getOnElicitationRequest());
        assertSame(handler, clone.getInteraction().getOnElicitationRequest());
    }

    @Test
    void buildCreateRequestSetsRequestElicitationWhenHandlerPresent() {
        ElicitationHandler handler = ctx -> CompletableFuture
                .completedFuture(new ElicitationResult().setAction(ElicitationResultAction.ACCEPT));

        var config = new CreateSessionOptions()
                .setPermissions(new SessionPermissionOptions().setOnRequest(PermissionHandler.APPROVE_ALL))
                .setInteraction(new SessionInteractionOptions().setOnElicitationRequest(handler));

        var request = SessionRequestBuilder.buildCreateRequest(config, "session-1");

        assertEquals(Boolean.TRUE, request.getInteraction().getRequestElicitation());
    }

    @Test
    void buildCreateRequestDoesNotSetRequestElicitationWhenNoHandler() {
        var config = new CreateSessionOptions()
                .setPermissions(new SessionPermissionOptions().setOnRequest(PermissionHandler.APPROVE_ALL));

        var request = SessionRequestBuilder.buildCreateRequest(config, "session-1");

        assertNull(request.getInteraction().getRequestElicitation());
    }

    @Test
    void buildResumeRequestSetsRequestElicitationWhenHandlerPresent() {
        ElicitationHandler handler = ctx -> CompletableFuture
                .completedFuture(new ElicitationResult().setAction(ElicitationResultAction.ACCEPT));

        var config = new ResumeSessionOptions()
                .setPermissions(new SessionPermissionOptions().setOnRequest(PermissionHandler.APPROVE_ALL))
                .setInteraction(new SessionInteractionOptions().setOnElicitationRequest(handler));

        var request = SessionRequestBuilder.buildResumeRequest("session-1", config);

        assertEquals(Boolean.TRUE, request.getInteraction().getRequestElicitation());
    }
}
