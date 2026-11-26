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
 * Runtime-derived identity of the connection that requested a host interaction.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HostCallbackCaller(
    /** Connection identifier assigned by this runtime, not supplied by the request. */
    @JsonProperty("connectionId") Long connectionId,
    /** Whether this runtime admitted the caller as an extension. */
    @JsonProperty("isExtension") Boolean isExtension
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public HostCallbackCaller {}

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
        private Long connectionId;
        private Boolean isExtension;

        /**
         * Sets the {@code connectionId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder connectionId(Long value) {
            this.connectionId = value;
            return this;
        }

        /**
         * Sets the {@code isExtension} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder isExtension(Boolean value) {
            this.isExtension = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public HostCallbackCaller build() {
            if (connectionId == null) throw new IllegalStateException("Missing required property: connectionId");
            if (isExtension == null) throw new IllegalStateException("Missing required property: isExtension");
            return new HostCallbackCaller(connectionId, isExtension);
        }
    }
}
