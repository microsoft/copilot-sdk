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
 * Local definitions and diagnostics from an admitted runtime session.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionSkillsGetDefinitionsResult(
    /** Definitions in canonical precedence order, including disabled local skills. */
    @JsonProperty("skills") List<LocalSkillDefinition> skills,
    /** Nonfatal diagnostics for loaded definitions. */
    @JsonProperty("warnings") List<String> warnings,
    /** Diagnostics for definitions that could not load. */
    @JsonProperty("errors") List<String> errors,
    /** Working directory used for relative diagnostic paths. */
    @JsonProperty("displayCwd") String displayCwd
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SessionSkillsGetDefinitionsResult {}

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
        private List<LocalSkillDefinition> skills;
        private List<String> warnings;
        private List<String> errors;
        private String displayCwd;

        /**
         * Sets the {@code skills} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder skills(List<LocalSkillDefinition> value) {
            this.skills = value;
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
         * Sets the {@code errors} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder errors(List<String> value) {
            this.errors = value;
            return this;
        }

        /**
         * Sets the {@code displayCwd} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder displayCwd(String value) {
            this.displayCwd = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SessionSkillsGetDefinitionsResult build() {
            if (skills == null) throw new IllegalStateException("Missing required property: skills");
            if (warnings == null) throw new IllegalStateException("Missing required property: warnings");
            if (errors == null) throw new IllegalStateException("Missing required property: errors");
            if (displayCwd == null) throw new IllegalStateException("Missing required property: displayCwd");
            return new SessionSkillsGetDefinitionsResult(skills, warnings, errors, displayCwd);
        }
    }
}
