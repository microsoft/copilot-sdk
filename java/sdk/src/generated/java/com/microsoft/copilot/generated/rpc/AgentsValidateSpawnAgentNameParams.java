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
 * Caller-visible agent identifiers for spawn validation. This operation does not load agents.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentsValidateSpawnAgentNameParams(
    /** Requested agent name or identifier. */
    @JsonProperty("name") String name,
    /** Custom agents already loaded by the caller. */
    @JsonProperty("customAgents") List<SpawnValidatableAgent> customAgents,
    /** Builtin names available in the caller's context. */
    @JsonProperty("builtinNames") List<String> builtinNames
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public AgentsValidateSpawnAgentNameParams {}

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
        private List<SpawnValidatableAgent> customAgents;
        private List<String> builtinNames;

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
         * Sets the {@code customAgents} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder customAgents(List<SpawnValidatableAgent> value) {
            this.customAgents = value;
            return this;
        }

        /**
         * Sets the {@code builtinNames} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder builtinNames(List<String> value) {
            this.builtinNames = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public AgentsValidateSpawnAgentNameParams build() {
            if (name == null) throw new IllegalStateException("Missing required property: name");
            if (customAgents == null) throw new IllegalStateException("Missing required property: customAgents");
            if (builtinNames == null) throw new IllegalStateException("Missing required property: builtinNames");
            return new AgentsValidateSpawnAgentNameParams(name, customAgents, builtinNames);
        }
    }
}
