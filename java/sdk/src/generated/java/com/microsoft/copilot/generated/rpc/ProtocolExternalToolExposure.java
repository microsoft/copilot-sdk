/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.microsoft.copilot.CopilotExperimental;
import javax.annotation.processing.Generated;

/**
 * Controls how agents receive an external tool definition. This is a visibility policy, not an execution permission.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public enum ProtocolExternalToolExposure {
    /** The {@code automatic} variant. */
    AUTOMATIC("automatic"),
    /** The {@code explicit} variant. */
    EXPLICIT("explicit");

    private final String value;
    ProtocolExternalToolExposure(String value) { this.value = value; }
    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() { return value; }
    @com.fasterxml.jackson.annotation.JsonCreator
    public static ProtocolExternalToolExposure fromValue(String value) {
        for (ProtocolExternalToolExposure v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unknown ProtocolExternalToolExposure value: " + value);
    }
}
