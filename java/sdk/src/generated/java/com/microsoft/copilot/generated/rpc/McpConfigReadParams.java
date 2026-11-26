/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.microsoft.copilot.CopilotExperimental;
import javax.annotation.processing.Generated;

/**
 * Persisted MCP document or fresh raw membership selection. Does not change the existing normalized list response.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record McpConfigReadParams(
    /** Configuration root override. Omitted uses the host's root without changing its authentication owner. */
    @JsonProperty("configDir") String configDir,
    /** Configuration name beneath mcp-config. Omitted or empty selects mcp-config.json, with the existing extensionless fallback. */
    @JsonProperty("subDir") String subDir,
    /** Result view. Omitted or document returns persisted JSON. Membership returns fresh raw user names without reading or clearing the validated cache. */
    @JsonProperty("view") McpConfigReadView view
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    McpConfigReadParams {}

    /**
     * Returns a named builder.
     * @return a new builder
     * @since 2.0.0
     */
    @com.microsoft.copilot.CopilotExperimental
    public static Builder builder() { return new Builder(); }

    /**
     * Named construction for this record.
     * @since 2.0.0
     */
    @com.microsoft.copilot.CopilotExperimental
    public static final class Builder {
        private Builder() {}
        private String configDir;
        private String subDir;
        private McpConfigReadView view;

        /**
         * Sets the {@code configDir} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder configDir(String value) {
            this.configDir = value;
            return this;
        }

        /**
         * Sets the {@code subDir} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder subDir(String value) {
            this.subDir = value;
            return this;
        }

        /**
         * Sets the {@code view} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder view(McpConfigReadView value) {
            this.view = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public McpConfigReadParams build() {
            return new McpConfigReadParams(configDir, subDir, view);
        }
    }
}
