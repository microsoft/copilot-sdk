/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import javax.annotation.processing.Generated;

/**
 * Persisted MCP configuration projection. The default document view returns persisted JSON.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
enum McpConfigReadView {
    /** The {@code document} variant. */
    DOCUMENT("document"),
    /** The {@code membership} variant. */
    MEMBERSHIP("membership");

    private final String value;
    McpConfigReadView(String value) { this.value = value; }
    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() { return value; }
    @com.fasterxml.jackson.annotation.JsonCreator
    public static McpConfigReadView fromValue(String value) {
        for (McpConfigReadView v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unknown McpConfigReadView value: " + value);
    }
}
