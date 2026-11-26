/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.microsoft.copilot.CopilotExperimental;
import java.util.concurrent.CompletableFuture;
import javax.annotation.processing.Generated;

/**
 * API methods for the {@code telemetry} namespace.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
final class ServerTelemetryApi {

    private final RpcCaller caller;

    /** @param caller the RPC transport function */
    ServerTelemetryApi(RpcCaller caller) {
        this.caller = caller;
    }

    /**
     * Updates the trusted in-process CLI host's process-wide editor attribution after protocol negotiation. The runtime validates bounded editor names and versions before telemetry export. An omitted or unrecognized editor clears attribution. This method requires private CLI host admission; request metadata never establishes authority.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<Void> setHostEditorAttribution(TelemetrySetHostEditorAttributionParams params) {
        return caller.invoke("telemetry.setHostEditorAttribution", params, Void.class);
    }

}
