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
 * Explicit discovery inputs for clients that compose their own source set.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillScanInputs(
    /** Monorepo traversal start. Omission scans only the explicit repository boundary. */
    @JsonProperty("traversalDirectory") String traversalDirectory,
    /** Repository boundary for project discovery. Omit it outside a repository. */
    @JsonProperty("projectRoot") String projectRoot,
    /** Additional user-configured skill directories. */
    @JsonProperty("customDirectories") List<String> customDirectories,
    /** Explicit skill directories and their source metadata. */
    @JsonProperty("skillSources") List<SkillScanSource> skillSources,
    /** Explicit command directories and their source metadata. */
    @JsonProperty("commandSources") List<SkillScanSource> commandSources
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SkillScanInputs {}

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
        private String traversalDirectory;
        private String projectRoot;
        private List<String> customDirectories;
        private List<SkillScanSource> skillSources;
        private List<SkillScanSource> commandSources;

        /**
         * Sets the {@code traversalDirectory} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder traversalDirectory(String value) {
            this.traversalDirectory = value;
            return this;
        }

        /**
         * Sets the {@code projectRoot} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder projectRoot(String value) {
            this.projectRoot = value;
            return this;
        }

        /**
         * Sets the {@code customDirectories} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder customDirectories(List<String> value) {
            this.customDirectories = value;
            return this;
        }

        /**
         * Sets the {@code skillSources} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder skillSources(List<SkillScanSource> value) {
            this.skillSources = value;
            return this;
        }

        /**
         * Sets the {@code commandSources} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder commandSources(List<SkillScanSource> value) {
            this.commandSources = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SkillScanInputs build() {
            if (customDirectories == null) throw new IllegalStateException("Missing required property: customDirectories");
            if (skillSources == null) throw new IllegalStateException("Missing required property: skillSources");
            if (commandSources == null) throw new IllegalStateException("Missing required property: commandSources");
            return new SkillScanInputs(traversalDirectory, projectRoot, customDirectories, skillSources, commandSources);
        }
    }
}
