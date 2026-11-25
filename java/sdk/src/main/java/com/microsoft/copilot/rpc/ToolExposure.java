/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.rpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Controls which agents automatically receive a {@link ToolDefinition}.
 * <p>
 * Omitting the value or using {@link #AUTOMATIC} preserves the default
 * behavior. {@link #EXPLICIT} makes the tool available only to custom agents
 * whose tool list explicitly names it. This controls model visibility, not
 * execution permission.
 *
 * @since 1.0.15-preview.1
 */
public enum ToolExposure {
    /**
     * The tool is available to the default agent and unrestricted custom agents.
     */
    AUTOMATIC("automatic"),

    /**
     * The tool is available only to custom agents whose tool list explicitly names
     * it.
     */
    EXPLICIT("explicit");

    private final String value;

    ToolExposure(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ToolExposure fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ToolExposure exposure : values()) {
            if (exposure.value.equals(value)) {
                return exposure;
            }
        }
        throw new IllegalArgumentException("Unknown ToolExposure value: " + value);
    }
}
