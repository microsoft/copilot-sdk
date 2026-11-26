/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.microsoft.copilot.CopilotExperimental;
import javax.annotation.processing.Generated;

/**
 * Extension source category permitted by managed policy.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public enum ManagedExtensionSource {
    /** The {@code host} variant. */
    HOST("host"),
    /** The {@code plugins} variant. */
    PLUGINS("plugins"),
    /** The {@code user} variant. */
    USER("user"),
    /** The {@code project} variant. */
    PROJECT("project"),
    /** The {@code session} variant. */
    SESSION("session");

    private final String value;
    ManagedExtensionSource(String value) { this.value = value; }
    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() { return value; }
    @com.fasterxml.jackson.annotation.JsonCreator
    public static ManagedExtensionSource fromValue(String value) {
        for (ManagedExtensionSource v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unknown ManagedExtensionSource value: " + value);
    }
}
