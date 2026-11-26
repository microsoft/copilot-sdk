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
 * A request to select a session in the controller's user interface.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HostForegroundSessionRequest(
    /** Requested session, or null to clear a rejected selection. Create or resume inactive sessions before accepting. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonProperty("sessionId") String sessionId,
    /** Runtime-derived identity of the requesting connection. */
    @JsonProperty("caller") HostCallbackCaller caller
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public HostForegroundSessionRequest {}

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
        private boolean $presence0;
        private HostCallbackCaller caller;

        /**
         * Sets the {@code sessionId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder sessionId(String value) {
            this.sessionId = value;
            this.$presence0 = true;
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
        public HostForegroundSessionRequest build() {
            if (!$presence0) throw new IllegalStateException("Missing required property: sessionId");
            if (caller == null) throw new IllegalStateException("Missing required property: caller");
            return new HostForegroundSessionRequest(sessionId, caller);
        }
    }
}
