/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.processing.Generated;

/**
 * One regular JSON file in the MCP configuration directory.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record McpConfigFile(
    /** File name relative to the MCP configuration directory. */
    @JsonProperty("file") String file,
    /** Modification time in milliseconds since the Unix epoch. */
    @JsonProperty("mtimeMs") Double mtimeMs,
    /** Birth time in milliseconds since the Unix epoch, using the existing platform fallback when unavailable. */
    @JsonProperty("birthtimeMs") Double birthtimeMs
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    McpConfigFile {}

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
        private String file;
        private Double mtimeMs;
        private Double birthtimeMs;

        /**
         * Sets the {@code file} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder file(String value) {
            this.file = value;
            return this;
        }

        /**
         * Sets the {@code mtimeMs} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder mtimeMs(Double value) {
            this.mtimeMs = value;
            return this;
        }

        /**
         * Sets the {@code birthtimeMs} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder birthtimeMs(Double value) {
            this.birthtimeMs = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public McpConfigFile build() {
            if (file == null) throw new IllegalStateException("Missing required property: file");
            if (mtimeMs == null) throw new IllegalStateException("Missing required property: mtimeMs");
            if (birthtimeMs == null) throw new IllegalStateException("Missing required property: birthtimeMs");
            return new McpConfigFile(file, mtimeMs, birthtimeMs);
        }
    }
}
