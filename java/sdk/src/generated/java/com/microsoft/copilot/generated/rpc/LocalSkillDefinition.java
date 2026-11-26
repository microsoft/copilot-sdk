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
 * Complete parsed definition for editor previews and inline skill expansion.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocalSkillDefinition(
    /** Declared name before invocation collision resolution. */
    @JsonProperty("name") String name,
    /** Description of the skill. */
    @JsonProperty("description") String description,
    /** Source that owns this definition. */
    @JsonProperty("source") LocalSkillDefinitionSource source,
    /** Absolute definition file path. */
    @JsonProperty("filePath") String filePath,
    /** Absolute directory that holds supporting files. */
    @JsonProperty("baseDir") String baseDir,
    /** Checkout boundary for fresh reads of repository definitions. */
    @JsonProperty("repositoryBoundary") String repositoryBoundary,
    /** Allowed tool specifications from frontmatter. */
    @JsonProperty("allowedTools") List<String> allowedTools,
    /** Complete skill file text, including YAML frontmatter. */
    @JsonProperty("content") String content,
    /** Whether a user can invoke this definition. */
    @JsonProperty("userInvocable") Boolean userInvocable,
    /** Whether model invocation is disabled. */
    @JsonProperty("disableModelInvocation") Boolean disableModelInvocation,
    /** Whether a command file supplied this definition. */
    @JsonProperty("isCommand") Boolean isCommand,
    /** Freeform argument hint for a command picker. */
    @JsonProperty("argumentHint") String argumentHint,
    /** Owning plugin's name. */
    @JsonProperty("pluginName") String pluginName,
    /** Owning plugin's version. */
    @JsonProperty("pluginVersion") String pluginVersion,
    /** Collision-resolved invocation identity. */
    @JsonProperty("invocationName") String invocationName
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public LocalSkillDefinition {}

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
        private String name;
        private String description;
        private LocalSkillDefinitionSource source;
        private String filePath;
        private String baseDir;
        private String repositoryBoundary;
        private List<String> allowedTools;
        private String content;
        private Boolean userInvocable;
        private Boolean disableModelInvocation;
        private Boolean isCommand;
        private String argumentHint;
        private String pluginName;
        private String pluginVersion;
        private String invocationName;

        /**
         * Sets the {@code name} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder name(String value) {
            this.name = value;
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
         * Sets the {@code filePath} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder filePath(String value) {
            this.filePath = value;
            return this;
        }

        /**
         * Sets the {@code baseDir} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder baseDir(String value) {
            this.baseDir = value;
            return this;
        }

        /**
         * Sets the {@code repositoryBoundary} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder repositoryBoundary(String value) {
            this.repositoryBoundary = value;
            return this;
        }

        /**
         * Sets the {@code allowedTools} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder allowedTools(List<String> value) {
            this.allowedTools = value;
            return this;
        }

        /**
         * Sets the {@code content} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder content(String value) {
            this.content = value;
            return this;
        }

        /**
         * Sets the {@code userInvocable} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder userInvocable(Boolean value) {
            this.userInvocable = value;
            return this;
        }

        /**
         * Sets the {@code disableModelInvocation} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder disableModelInvocation(Boolean value) {
            this.disableModelInvocation = value;
            return this;
        }

        /**
         * Sets the {@code isCommand} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder isCommand(Boolean value) {
            this.isCommand = value;
            return this;
        }

        /**
         * Sets the {@code argumentHint} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder argumentHint(String value) {
            this.argumentHint = value;
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
         * Sets the {@code invocationName} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder invocationName(String value) {
            this.invocationName = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public LocalSkillDefinition build() {
            if (name == null) throw new IllegalStateException("Missing required property: name");
            if (description == null) throw new IllegalStateException("Missing required property: description");
            if (source == null) throw new IllegalStateException("Missing required property: source");
            if (filePath == null) throw new IllegalStateException("Missing required property: filePath");
            if (baseDir == null) throw new IllegalStateException("Missing required property: baseDir");
            if (allowedTools == null) throw new IllegalStateException("Missing required property: allowedTools");
            if (content == null) throw new IllegalStateException("Missing required property: content");
            if (userInvocable == null) throw new IllegalStateException("Missing required property: userInvocable");
            if (disableModelInvocation == null) throw new IllegalStateException("Missing required property: disableModelInvocation");
            if (isCommand == null) throw new IllegalStateException("Missing required property: isCommand");
            return new LocalSkillDefinition(name, description, source, filePath, baseDir, repositoryBoundary, allowedTools, content, userInvocable, disableModelInvocation, isCommand, argumentHint, pluginName, pluginVersion, invocationName);
        }
    }
}
