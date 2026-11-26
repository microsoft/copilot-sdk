/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.processing.Generated;

/**
 * Persisted MCP document or raw user membership. Separate from the existing normalized list result to preserve public SDK construction patterns.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record McpConfigReadResult(
    /** Validated persisted JSON, including unknown document fields. Present only for the document view when the file exists. Installation-owned entries are visible only to the admitted host. */
    @JsonProperty("configJson") String configJson,
    /** Fresh raw user server names, including invalid entries and excluding installation-owned entries. Present only for the membership view. */
    @JsonProperty("names") List<String> names
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    McpConfigReadResult {}

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
        private String configJson;
        private List<String> names;

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
         * Sets the {@code names} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder names(List<String> value) {
            this.names = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public McpConfigReadResult build() {
            return new McpConfigReadResult(configJson, names);
        }
    }
}
