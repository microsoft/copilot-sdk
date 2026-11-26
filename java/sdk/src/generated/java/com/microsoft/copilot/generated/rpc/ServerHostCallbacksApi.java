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
 * API methods for the {@code host.callbacks} namespace.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public final class ServerHostCallbacksApi {

    private final RpcCaller caller;

    /** @param caller the RPC transport function */
    ServerHostCallbacksApi(RpcCaller caller) {
        this.caller = caller;
    }

    /**
     * Registers narrow foreground and controller-local spawn callbacks on the original SDK-owned runtime connection. Other connections cannot register them. Replaces this owner's previous registration and cancels its pending callbacks. Omitted flags disable their callback; disabling both clears the registration. The runtime keeps canonical session ownership.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> register(HostCallbacksRegisterParams params) {
        return caller.invoke("host.callbacks.register", params, Void.class);
    }

}
