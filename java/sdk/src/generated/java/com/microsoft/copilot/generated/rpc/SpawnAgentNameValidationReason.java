/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import javax.annotation.processing.Generated;

/**
 * Reason an agent name cannot be used for spawn.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public enum SpawnAgentNameValidationReason {
    /** The {@code unknown-agent} variant. */
    UNKNOWN_AGENT("unknown-agent");

    private final String value;
    SpawnAgentNameValidationReason(String value) { this.value = value; }
    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() { return value; }
    @com.fasterxml.jackson.annotation.JsonCreator
    public static SpawnAgentNameValidationReason fromValue(String value) {
        for (SpawnAgentNameValidationReason v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unknown SpawnAgentNameValidationReason value: " + value);
    }
}
