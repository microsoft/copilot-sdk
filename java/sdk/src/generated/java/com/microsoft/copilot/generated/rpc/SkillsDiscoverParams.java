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
 * Optional project paths and additional skill directories to include in discovery.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = SkillsDiscoverParams.$NullableDeserializer.class)
public record SkillsDiscoverParams(
    /** Configuration directory override. Omission uses the server context. */
    @JsonProperty("configDir") String configDir,
    /** Host home directory for personal discovery roots. */
    @JsonProperty("homeDirectory") String homeDirectory,
    /** Host-resolved directory for bundled skill definitions. */
    @JsonProperty("builtinDir") String builtinDir,
    /** Excludes user and workspace sources for a policy-constrained preview. */
    @JsonProperty("blockUserAndWorkspaceSkills") Boolean blockUserAndWorkspaceSkills,
    /** Complete settings object for an unsaved configuration preview. */
    @JsonProperty("configOverride") Object configOverride,
    /** Optional list of project directory paths to scan for project-scoped skills */
    @JsonProperty("projectPaths") List<String> projectPaths,
    /** Explicit discovery-only skill roots, not filesystem grants. Available without ambient discovery, subject to skill/managed disables. Relative paths resolve against the request's effective working directory when supplied. Null and omission are equivalent; an empty array contributes no explicit roots. */
    @JsonProperty("skillDirectories") List<String> skillDirectories,
    /** Optional skill scan paths to exclude from discovery. */
    @JsonProperty("ignoredSkillsLocations") List<String> ignoredSkillsLocations,
    /** When true, omit skills from the host's global sources (personal, custom, plugin, and built-in), returning only project-scoped skills. For multitenant deployments. */
    @JsonProperty("excludeHostSkills") Boolean excludeHostSkills,
    @com.fasterxml.jackson.annotation.JsonIgnore java.util.Set<String> $explicitNulls
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SkillsDiscoverParams { $explicitNulls = $explicitNulls == null ? java.util.Set.of() : java.util.Set.copyOf($explicitNulls); }

    /**
     * Creates a record without optional runtime metadata.
     * @param projectPaths the {@code projectPaths} property
     * @param skillDirectories the {@code skillDirectories} property
     * @param ignoredSkillsLocations the {@code ignoredSkillsLocations} property
     * @param excludeHostSkills the {@code excludeHostSkills} property
     */
    public SkillsDiscoverParams(List<String> projectPaths, List<String> skillDirectories, List<String> ignoredSkillsLocations, Boolean excludeHostSkills) {
        this(null, null, null, null, null, projectPaths, skillDirectories, ignoredSkillsLocations, excludeHostSkills, java.util.Set.of());
    }

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
        private String homeDirectory;
        private String builtinDir;
        private Boolean blockUserAndWorkspaceSkills;
        private Object configOverride;
        private boolean $presence4;
        private List<String> projectPaths;
        private List<String> skillDirectories;
        private List<String> ignoredSkillsLocations;
        private Boolean excludeHostSkills;

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
         * Sets the {@code homeDirectory} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder homeDirectory(String value) {
            this.homeDirectory = value;
            return this;
        }

        /**
         * Sets the {@code builtinDir} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder builtinDir(String value) {
            this.builtinDir = value;
            return this;
        }

        /**
         * Sets the {@code blockUserAndWorkspaceSkills} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder blockUserAndWorkspaceSkills(Boolean value) {
            this.blockUserAndWorkspaceSkills = value;
            return this;
        }

        /**
         * Sets the {@code configOverride} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder configOverride(Object value) {
            this.configOverride = value;
            this.$presence4 = true;
            return this;
        }

        /**
         * Sets the {@code projectPaths} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder projectPaths(List<String> value) {
            this.projectPaths = value;
            return this;
        }

        /**
         * Sets the {@code skillDirectories} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder skillDirectories(List<String> value) {
            this.skillDirectories = value;
            return this;
        }

        /**
         * Sets the {@code ignoredSkillsLocations} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder ignoredSkillsLocations(List<String> value) {
            this.ignoredSkillsLocations = value;
            return this;
        }

        /**
         * Sets the {@code excludeHostSkills} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder excludeHostSkills(Boolean value) {
            this.excludeHostSkills = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SkillsDiscoverParams build() {
            java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();
            if ($presence4 && configOverride == null) $explicitNulls.add("configOverride");
            return new SkillsDiscoverParams(configDir, homeDirectory, builtinDir, blockUserAndWorkspaceSkills, configOverride, projectPaths, skillDirectories, ignoredSkillsLocations, excludeHostSkills, $explicitNulls);
        }
    }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
    public java.util.Map<String, Object> $nullableProperties() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        if ($explicitNulls.contains("configOverride") && configOverride == null) values.put("configOverride", null);
        return values;
    }

    public static final class $NullableDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<SkillsDiscoverParams> {
        @Override
        public SkillsDiscoverParams deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode tree = context.readTree(parser);
            if (!tree.isObject()) return context.reportInputMismatch(SkillsDiscoverParams.class, "Expected a JSON object");
            java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();
            if (tree.has("configOverride") && tree.get("configOverride").isNull()) $explicitNulls.add("configOverride");
            return new SkillsDiscoverParams(readProperty(context, tree, "configDir", new com.fasterxml.jackson.core.type.TypeReference<String>() {}), readProperty(context, tree, "homeDirectory", new com.fasterxml.jackson.core.type.TypeReference<String>() {}), readProperty(context, tree, "builtinDir", new com.fasterxml.jackson.core.type.TypeReference<String>() {}), readProperty(context, tree, "blockUserAndWorkspaceSkills", new com.fasterxml.jackson.core.type.TypeReference<Boolean>() {}), readProperty(context, tree, "configOverride", new com.fasterxml.jackson.core.type.TypeReference<Object>() {}), readProperty(context, tree, "projectPaths", new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}), readProperty(context, tree, "skillDirectories", new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}), readProperty(context, tree, "ignoredSkillsLocations", new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}), readProperty(context, tree, "excludeHostSkills", new com.fasterxml.jackson.core.type.TypeReference<Boolean>() {}), $explicitNulls);
        }

        private static <T> T readProperty(com.fasterxml.jackson.databind.DeserializationContext context,
                com.fasterxml.jackson.databind.JsonNode tree, String name,
                com.fasterxml.jackson.core.type.TypeReference<T> type) throws java.io.IOException {
            if (!tree.has(name)) return null;
            return context.readTreeAsValue(tree.get(name), context.getTypeFactory().constructType(type));
        }
    }
}
