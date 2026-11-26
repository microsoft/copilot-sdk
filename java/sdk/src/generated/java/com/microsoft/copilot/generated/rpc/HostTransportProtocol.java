/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.microsoft.copilot.CopilotExperimental;
import javax.annotation.processing.Generated;

/**
 * Protocol served by a runtime-owned transport.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public enum HostTransportProtocol {
    /** The {@code json-rpc} variant. */
    JSON_RPC("json-rpc"),
    /** The {@code acp} variant. */
    ACP("acp");

    private final String value;
    HostTransportProtocol(String value) { this.value = value; }
    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() { return value; }
    @com.fasterxml.jackson.annotation.JsonCreator
    public static HostTransportProtocol fromValue(String value) {
        for (HostTransportProtocol v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unknown HostTransportProtocol value: " + value);
    }
}
