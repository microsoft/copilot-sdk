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
 * Regular JSON files in the MCP configuration directory, newest modification time first. Unreadable or absent directories return an empty list.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record McpConfigFilesResult(
    /** File metadata with stable ordering for equal whole-millisecond modification times. */
    @JsonProperty("files") List<McpConfigFile> files
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    McpConfigFilesResult {}

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
        private List<McpConfigFile> files;

        /**
         * Sets the {@code files} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder files(List<McpConfigFile> value) {
            this.files = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public McpConfigFilesResult build() {
            if (files == null) throw new IllegalStateException("Missing required property: files");
            return new McpConfigFilesResult(files);
        }
    }
}
