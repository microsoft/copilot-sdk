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
 * Directory metadata resolved by the runtime for an explicit plugin mount.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginDirectoryMetadata(
    /** Plugin cache path identifying the corresponding installed-plugin record. */
    @JsonProperty("pluginPath") String pluginPath,
    /** Canonical directory eligible for the host's existing plugin read grant. */
    @JsonProperty("canonicalDirectory") String canonicalDirectory,
    /** Description reported by the plugin manifest. */
    @JsonProperty("description") String description
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public PluginDirectoryMetadata {}

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
        private String pluginPath;
        private String canonicalDirectory;
        private String description;

        /**
         * Sets the {@code pluginPath} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder pluginPath(String value) {
            this.pluginPath = value;
            return this;
        }

        /**
         * Sets the {@code canonicalDirectory} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder canonicalDirectory(String value) {
            this.canonicalDirectory = value;
            return this;
        }

        /**
         * Sets the {@code description} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public PluginDirectoryMetadata build() {
            if (pluginPath == null) throw new IllegalStateException("Missing required property: pluginPath");
            return new PluginDirectoryMetadata(pluginPath, canonicalDirectory, description);
        }
    }
}
