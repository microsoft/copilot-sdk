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
 * A skill or command directory supplied explicitly by a client.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillScanSource(
    /** Stable source identifier for an owning plugin, when supplied. */
    @JsonProperty("pluginId") String pluginId,
    /** Absolute directory to scan. */
    @JsonProperty("path") String path,
    /** Discovery source, such as plugin, project, or custom. */
    @JsonProperty("source") LocalSkillDefinitionSource source,
    /** Owning plugin's name, when applicable. */
    @JsonProperty("pluginName") String pluginName,
    /** Owning plugin's version, when applicable. */
    @JsonProperty("pluginVersion") String pluginVersion,
    /** Source precedence tier, when explicitly supplied. */
    @JsonProperty("tier") String tier
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SkillScanSource {}

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
        private String pluginId;
        private String path;
        private LocalSkillDefinitionSource source;
        private String pluginName;
        private String pluginVersion;
        private String tier;

        /**
         * Sets the {@code pluginId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder pluginId(String value) {
            this.pluginId = value;
            return this;
        }

        /**
         * Sets the {@code path} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder path(String value) {
            this.path = value;
            return this;
        }

        /**
         * Sets the {@code source} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder source(LocalSkillDefinitionSource value) {
            this.source = value;
            return this;
        }

        /**
         * Sets the {@code pluginName} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder pluginName(String value) {
            this.pluginName = value;
            return this;
        }

        /**
         * Sets the {@code pluginVersion} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder pluginVersion(String value) {
            this.pluginVersion = value;
            return this;
        }

        /**
         * Sets the {@code tier} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder tier(String value) {
            this.tier = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SkillScanSource build() {
            if (path == null) throw new IllegalStateException("Missing required property: path");
            if (source == null) throw new IllegalStateException("Missing required property: source");
            return new SkillScanSource(pluginId, path, source, pluginName, pluginVersion, tier);
        }
    }
}
