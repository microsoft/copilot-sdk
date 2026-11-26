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
 * A controller-local launch request shared unchanged by preparation and commit, not an SDK session creation callback.
Preparation must not launch a child or retain request resources.
The launch callback must not await validation or controller queries before launch. It may await registry readiness afterward.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HostAgentRegistrySpawnRequest(
    /** Runtime-issued identity shared by preparation and the launch commit for these exact inputs. */
    @JsonProperty("requestId") String requestId,
    /** Original launch inputs. Preparation validates them without launch effects or retained request resources. */
    @JsonProperty("request") AgentRegistrySpawnRequest request,
    /** Controller session whose runtime permission policy governs launch admission after preparation. */
    @JsonProperty("controllerSessionId") String controllerSessionId,
    /** Runtime-derived identity of the requesting connection. */
    @JsonProperty("caller") HostCallbackCaller caller
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public HostAgentRegistrySpawnRequest {}

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
        private String requestId;
        private AgentRegistrySpawnRequest request;
        private String controllerSessionId;
        private HostCallbackCaller caller;

        /**
         * Sets the {@code requestId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder requestId(String value) {
            this.requestId = value;
            return this;
        }

        /**
         * Sets the {@code request} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder request(AgentRegistrySpawnRequest value) {
            this.request = value;
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
         * Sets the {@code caller} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder caller(HostCallbackCaller value) {
            this.caller = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public HostAgentRegistrySpawnRequest build() {
            if (requestId == null) throw new IllegalStateException("Missing required property: requestId");
            if (request == null) throw new IllegalStateException("Missing required property: request");
            if (controllerSessionId == null) throw new IllegalStateException("Missing required property: controllerSessionId");
            if (caller == null) throw new IllegalStateException("Missing required property: caller");
            return new HostAgentRegistrySpawnRequest(requestId, request, controllerSessionId, caller);
        }
    }
}
