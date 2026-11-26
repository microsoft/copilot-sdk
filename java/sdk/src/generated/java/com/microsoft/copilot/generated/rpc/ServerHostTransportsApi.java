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
 * API methods for the {@code host.transports} namespace.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public final class ServerHostTransportsApi {

    private final RpcCaller caller;

    /** @param caller the RPC transport function */
    ServerHostTransportsApi(RpcCaller caller) {
        this.caller = caller;
    }

    /**
     * Starts a runtime-owned JSON-RPC or ACP transport on this connection's existing engine. Requires native SDK host-controller admission; ordinary protocol clients cannot start listeners. The runtime owns connections and cleanup.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<HostTransportsStartResult> start(HostTransportsStartParams params) {
        return caller.invoke("host.transports.start", params, HostTransportsStartResult.class);
    }

    /**
     * Stops a transport owned by this SDK connection and waits for its connections to finish cleanup. Does not stop the runtime host or other transports.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> stop(HostTransportsStopParams params) {
        return caller.invoke("host.transports.stop", params, Void.class);
    }

    /**
     * Waits for an owned transport to end and reports transport failures. Waiting does not request shutdown; owner disconnection or runtime shutdown stops the transport.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> wait(HostTransportsWaitParams params) {
        return caller.invoke("host.transports.wait", params, Void.class);
    }

}
