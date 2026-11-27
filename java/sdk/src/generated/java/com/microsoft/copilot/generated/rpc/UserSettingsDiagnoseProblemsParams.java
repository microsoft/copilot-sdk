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
 * The settings layers to diagnose.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record UserSettingsDiagnoseProblemsParams(
    /** Layers to diagnose, in the precedence order the caller applies them. */
    @JsonProperty("sources") List<UserSettingsProblemSource> sources,
    /** Whether the caller's own settings loader allows localhost and plaintext-HTTP hooks. `false` reports those hooks as problems even when the server's environment allows them. `true` or absent leaves the server's environment in charge; it never allows a hook the server rejects. */
    @JsonProperty("allowLocalhostHooks") Boolean allowLocalhostHooks,
    /** Whether the caller's own settings loader allows authorization-affecting HTTP hooks. `false` reports those hooks as problems even when the server's environment allows them. `true` or absent leaves the server's environment in charge; it never allows a hook the server rejects. */
    @JsonProperty("allowHttpAuthHooks") Boolean allowHttpAuthHooks
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    UserSettingsDiagnoseProblemsParams {}

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
        private List<UserSettingsProblemSource> sources;
        private Boolean allowLocalhostHooks;
        private Boolean allowHttpAuthHooks;

        /**
         * Sets the {@code sources} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder sources(List<UserSettingsProblemSource> value) {
            this.sources = value;
            return this;
        }

        /**
         * Sets the {@code allowLocalhostHooks} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder allowLocalhostHooks(Boolean value) {
            this.allowLocalhostHooks = value;
            return this;
        }

        /**
         * Sets the {@code allowHttpAuthHooks} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder allowHttpAuthHooks(Boolean value) {
            this.allowHttpAuthHooks = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public UserSettingsDiagnoseProblemsParams build() {
            return new UserSettingsDiagnoseProblemsParams(sources, allowLocalhostHooks, allowHttpAuthHooks);
        }
    }
}
