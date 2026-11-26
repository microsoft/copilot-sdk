/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import javax.annotation.processing.Generated;

/**
 * Reason a working directory cannot be used for spawn.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public enum SpawnCwdValidationReason {
    /** The {@code cwd-not-found} variant. */
    CWD_NOT_FOUND("cwd-not-found"),
    /** The {@code cwd-not-directory} variant. */
    CWD_NOT_DIRECTORY("cwd-not-directory");

    private final String value;
    SpawnCwdValidationReason(String value) { this.value = value; }
    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() { return value; }
    @com.fasterxml.jackson.annotation.JsonCreator
    public static SpawnCwdValidationReason fromValue(String value) {
        for (SpawnCwdValidationReason v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unknown SpawnCwdValidationReason value: " + value);
    }
}
