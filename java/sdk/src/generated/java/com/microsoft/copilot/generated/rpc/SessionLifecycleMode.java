/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import javax.annotation.processing.Generated;

/**
 * Hook lifetime for a local session.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public enum SessionLifecycleMode {
    /** The {@code interactive} variant. */
    INTERACTIVE("interactive"),
    /** The {@code per-turn} variant. */
    PER_TURN("per-turn");

    private final String value;
    SessionLifecycleMode(String value) { this.value = value; }
    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() { return value; }
    @com.fasterxml.jackson.annotation.JsonCreator
    public static SessionLifecycleMode fromValue(String value) {
        for (SessionLifecycleMode v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unknown SessionLifecycleMode value: " + value);
    }
}
