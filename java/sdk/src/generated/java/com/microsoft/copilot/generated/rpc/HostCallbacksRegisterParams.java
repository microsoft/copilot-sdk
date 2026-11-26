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
import javax.annotation.processing.Generated;

/**
 * Callbacks supplied by the original SDK-owned runtime controller.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HostCallbacksRegisterParams(
    /** Ask this connection to select the session displayed by its user interface. Defaults to false. */
    @JsonProperty("foregroundSessionChange") Boolean foregroundSessionChange,
    /** Enable controller-local preparation and launch callbacks. The host's SESSIONS_SIDEBAR_TAB flag must also be enabled. */
    @JsonProperty("agentRegistrySpawn") Boolean agentRegistrySpawn,
    /** Local session owned by this connection whose permission policy governs spawn. Required when spawn is enabled. */
    @JsonProperty("controllerSessionId") String controllerSessionId
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public HostCallbacksRegisterParams {}

    /**
     * Returns a named builder.
     * @return a new builder
     * @since 2.0.0
     */
    @com.microsoft.copilot.CopilotExperimental
    public static Builder builder() { return new Builder(); }

    /**
     * Named construction for this record.
     * @since 2.0.0
     */
    @com.microsoft.copilot.CopilotExperimental
    public static final class Builder {
        private Builder() {}
        private Boolean foregroundSessionChange;
        private Boolean agentRegistrySpawn;
        private String controllerSessionId;

        /**
         * Sets the {@code foregroundSessionChange} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder foregroundSessionChange(Boolean value) {
            this.foregroundSessionChange = value;
            return this;
        }

        /**
         * Sets the {@code agentRegistrySpawn} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder agentRegistrySpawn(Boolean value) {
            this.agentRegistrySpawn = value;
            return this;
        }

        /**
         * Sets the {@code controllerSessionId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder controllerSessionId(String value) {
            this.controllerSessionId = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public HostCallbacksRegisterParams build() {
            return new HostCallbacksRegisterParams(foregroundSessionChange, agentRegistrySpawn, controllerSessionId);
        }
    }
}
