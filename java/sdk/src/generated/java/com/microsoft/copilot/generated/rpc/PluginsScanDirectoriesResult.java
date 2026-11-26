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
 * Recognized directory mounts and their manifest diagnostics.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginsScanDirectoriesResult(
    /** Synthetic installed-plugin records for valid directories. */
    @JsonProperty("plugins") List<InstalledPlugin> plugins,
    /** Runtime-resolved directory metadata, in the same order as the plugin records. */
    @JsonProperty("directories") List<PluginDirectoryMetadata> directories,
    /** Report-and-ignore diagnostics for rejected directories or manifest fields. */
    @JsonProperty("warnings") List<String> warnings
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public PluginsScanDirectoriesResult {}

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
        private List<InstalledPlugin> plugins;
        private List<PluginDirectoryMetadata> directories;
        private List<String> warnings;

        /**
         * Sets the {@code plugins} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder plugins(List<InstalledPlugin> value) {
            this.plugins = value;
            return this;
        }

        /**
         * Sets the {@code directories} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder directories(List<PluginDirectoryMetadata> value) {
            this.directories = value;
            return this;
        }

        /**
         * Sets the {@code warnings} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder warnings(List<String> value) {
            this.warnings = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public PluginsScanDirectoriesResult build() {
            if (plugins == null) throw new IllegalStateException("Missing required property: plugins");
            if (directories == null) throw new IllegalStateException("Missing required property: directories");
            if (warnings == null) throw new IllegalStateException("Missing required property: warnings");
            return new PluginsScanDirectoriesResult(plugins, directories, warnings);
        }
    }
}
