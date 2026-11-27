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
 * Repository and name of a remote custom agent whose authored prompt to fetch.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record SessionAgentFetchRemotePromptParams(
    /** Target session identifier */
    @JsonProperty("sessionId") String sessionId,
    /** Owner of the repository that defines the remote agent. */
    @JsonProperty("repoOwner") String repoOwner,
    /** Name of the repository that defines the remote agent. */
    @JsonProperty("repoName") String repoName,
    /** Name of the remote agent. */
    @JsonProperty("agentName") String agentName
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    SessionAgentFetchRemotePromptParams {}

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
        private String sessionId;
        private String repoOwner;
        private String repoName;
        private String agentName;

        /**
         * Sets the {@code sessionId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder sessionId(String value) {
            this.sessionId = value;
            return this;
        }

        /**
         * Sets the {@code repoOwner} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder repoOwner(String value) {
            this.repoOwner = value;
            return this;
        }

        /**
         * Sets the {@code repoName} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder repoName(String value) {
            this.repoName = value;
            return this;
        }

        /**
         * Sets the {@code agentName} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder agentName(String value) {
            this.agentName = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SessionAgentFetchRemotePromptParams build() {
            if (repoOwner == null) throw new IllegalStateException("Missing required property: repoOwner");
            if (repoName == null) throw new IllegalStateException("Missing required property: repoName");
            if (agentName == null) throw new IllegalStateException("Missing required property: agentName");
            return new SessionAgentFetchRemotePromptParams(sessionId, repoOwner, repoName, agentName);
        }
    }
}
