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
import java.util.Map;
import javax.annotation.processing.Generated;

/**
 * Local discovery options. The runtime owns configuration, parsing, and caches.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillsLoadParams(
    /** Working directory for monorepo traversal and error display. */
    @JsonProperty("cwd") String cwd,
    /** Configuration directory override. Omission uses the server context. */
    @JsonProperty("configDir") String configDir,
    /** Host home directory. Omission uses the runtime's home directory. */
    @JsonProperty("homeDirectory") String homeDirectory,
    /** Host skill-directory environment value. Empty disables environment roots.
Omission reads COPILOT_SKILLS_DIRS from the runtime environment. */
    @JsonProperty("environmentSkillDirectories") String environmentSkillDirectories,
    /** Directory that contains the host's bundled skills.
Lockdown uses the runtime bundle instead. */
    @JsonProperty("builtinDirectory") String builtinDirectory,
    /** Explicit sources instead of runtime configuration and plugin resolution.
Lockdown ignores caller source labels and resolves plugin sources itself. */
    @JsonProperty("inputs") SkillScanInputs inputs,
    /** Session-only plugins, such as directories mounted by the host. */
    @JsonProperty("additionalPlugins") List<InstalledPlugin> additionalPlugins,
    /** Live user and repository plugin enablement, when the client has a preview. */
    @JsonProperty("enabledPlugins") Map<String, Boolean> enabledPlugins,
    /** Supplied soft Team defaults avoid a separate Team lookup.
Explicit settings and managed policy take precedence. */
    @JsonProperty("teamDefaultEnabledPlugins") Map<String, Boolean> teamDefaultEnabledPlugins,
    /** Whether to include ambient configuration discovery. Defaults to true. */
    @JsonProperty("enableConfigDiscovery") Boolean enableConfigDiscovery,
    /** Whether to scan skills. False returns no definitions. Defaults to true. */
    @JsonProperty("enableSkills") Boolean enableSkills,
    /** Explicit excluded locations. An empty list overrides configured exclusions. */
    @JsonProperty("ignoredSkillLocations") List<String> ignoredSkillLocations,
    /** Keeps only plugin and builtin sources during skills lockdown. */
    @JsonProperty("blockUserAndWorkspaceSkills") Boolean blockUserAndWorkspaceSkills,
    /** Whether to reuse parsed definitions. Defaults to true. */
    @JsonProperty("useCache") Boolean useCache,
    /** Checks source contents before reading cached definitions. */
    @JsonProperty("checkSources") Boolean checkSources,
    /** Checks sources without loading definitions or refreshing optional Team defaults.
Defaults to false. */
    @JsonProperty("checkOnly") Boolean checkOnly
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SkillsLoadParams {}

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
        private String cwd;
        private String configDir;
        private String homeDirectory;
        private String environmentSkillDirectories;
        private String builtinDirectory;
        private SkillScanInputs inputs;
        private List<InstalledPlugin> additionalPlugins;
        private Map<String, Boolean> enabledPlugins;
        private Map<String, Boolean> teamDefaultEnabledPlugins;
        private Boolean enableConfigDiscovery;
        private Boolean enableSkills;
        private List<String> ignoredSkillLocations;
        private Boolean blockUserAndWorkspaceSkills;
        private Boolean useCache;
        private Boolean checkSources;
        private Boolean checkOnly;

        /**
         * Sets the {@code cwd} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder cwd(String value) {
            this.cwd = value;
            return this;
        }

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
         * Sets the {@code environmentSkillDirectories} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder environmentSkillDirectories(String value) {
            this.environmentSkillDirectories = value;
            return this;
        }

        /**
         * Sets the {@code builtinDirectory} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder builtinDirectory(String value) {
            this.builtinDirectory = value;
            return this;
        }

        /**
         * Sets the {@code inputs} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder inputs(SkillScanInputs value) {
            this.inputs = value;
            return this;
        }

        /**
         * Sets the {@code additionalPlugins} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder additionalPlugins(List<InstalledPlugin> value) {
            this.additionalPlugins = value;
            return this;
        }

        /**
         * Sets the {@code enabledPlugins} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder enabledPlugins(Map<String, Boolean> value) {
            this.enabledPlugins = value;
            return this;
        }

        /**
         * Sets the {@code teamDefaultEnabledPlugins} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder teamDefaultEnabledPlugins(Map<String, Boolean> value) {
            this.teamDefaultEnabledPlugins = value;
            return this;
        }

        /**
         * Sets the {@code enableConfigDiscovery} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder enableConfigDiscovery(Boolean value) {
            this.enableConfigDiscovery = value;
            return this;
        }

        /**
         * Sets the {@code enableSkills} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder enableSkills(Boolean value) {
            this.enableSkills = value;
            return this;
        }

        /**
         * Sets the {@code ignoredSkillLocations} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder ignoredSkillLocations(List<String> value) {
            this.ignoredSkillLocations = value;
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
         * Sets the {@code useCache} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder useCache(Boolean value) {
            this.useCache = value;
            return this;
        }

        /**
         * Sets the {@code checkSources} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder checkSources(Boolean value) {
            this.checkSources = value;
            return this;
        }

        /**
         * Sets the {@code checkOnly} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder checkOnly(Boolean value) {
            this.checkOnly = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SkillsLoadParams build() {
            if (cwd == null) throw new IllegalStateException("Missing required property: cwd");
            return new SkillsLoadParams(cwd, configDir, homeDirectory, environmentSkillDirectories, builtinDirectory, inputs, additionalPlugins, enabledPlugins, teamDefaultEnabledPlugins, enableConfigDiscovery, enableSkills, ignoredSkillLocations, blockUserAndWorkspaceSkills, useCache, checkSources, checkOnly);
        }
    }
}
