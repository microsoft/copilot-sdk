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
 * Atomic bulk MCP document write. Uses the same runtime-owned installation leases, validation, metadata preservation, and cache update as persisted configuration writes.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record McpConfigWriteParams(
    /** Configuration root override. Omitted uses the host's root without changing its authentication owner. */
    @JsonProperty("configDir") String configDir,
    /** Configuration name beneath mcp-config. Omitted or empty selects mcp-config.json, with the existing extensionless fallback. */
    @JsonProperty("subDir") String subDir,
    /** Complete MCP configuration JSON document. Unknown top-level fields remain intact. Invalid server entries use the existing persistence validation rules. */
    @JsonProperty("configJson") String configJson
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    McpConfigWriteParams {}

    /**
     * Returns a named builder.
     * @return a new builder
     * @since 2.0.0
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Named construction for this record.
     * @since 2.0.0
     */
    public static final class Builder {
        private Builder() {}
        private String configDir;
        private String subDir;
        private String configJson;

        /**
         * Sets the {@code configDir} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
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
        public Builder subDir(String value) {
            this.subDir = value;
            return this;
        }

        /**
         * Sets the {@code configJson} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder configJson(String value) {
            this.configJson = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public McpConfigWriteParams build() {
            if (configJson == null) throw new IllegalStateException("Missing required property: configJson");
            return new McpConfigWriteParams(configDir, subDir, configJson);
        }
    }
}
