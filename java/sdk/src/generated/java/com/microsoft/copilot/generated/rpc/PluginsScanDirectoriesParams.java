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
import java.util.List;
import javax.annotation.processing.Generated;

/**
 * Explicit directories to inspect without installing or activating their contents.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginsScanDirectoriesParams(
    /** Plugin directories in caller precedence order. */
    @JsonProperty("paths") List<String> paths,
    /** Base directory for relative paths. Empty retains launcher-relative resolution. */
    @JsonProperty("workingDirectory") String workingDirectory
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public PluginsScanDirectoriesParams {}

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
        private List<String> paths;
        private String workingDirectory;

        /**
         * Sets the {@code paths} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder paths(List<String> value) {
            this.paths = value;
            return this;
        }

        /**
         * Sets the {@code workingDirectory} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder workingDirectory(String value) {
            this.workingDirectory = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public PluginsScanDirectoriesParams build() {
            if (paths == null) throw new IllegalStateException("Missing required property: paths");
            if (workingDirectory == null) throw new IllegalStateException("Missing required property: workingDirectory");
            return new PluginsScanDirectoriesParams(paths, workingDirectory);
        }
    }
}
