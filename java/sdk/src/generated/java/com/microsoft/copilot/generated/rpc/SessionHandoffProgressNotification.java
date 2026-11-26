/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.processing.Generated;

/**
 * Progress for one remote handoff requested through `sessions.open`.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionHandoffProgressNotification(
    /** The callback ID supplied by the handoff requester. */
    @JsonProperty("callbackId") String callbackId,
    /** The runtime-owned handoff step and its current status. */
    @JsonProperty("update") SessionsOpenProgress update
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SessionHandoffProgressNotification {}

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
        private String callbackId;
        private SessionsOpenProgress update;

        /**
         * Sets the {@code callbackId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder callbackId(String value) {
            this.callbackId = value;
            return this;
        }

        /**
         * Sets the {@code update} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder update(SessionsOpenProgress value) {
            this.update = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SessionHandoffProgressNotification build() {
            if (callbackId == null) throw new IllegalStateException("Missing required property: callbackId");
            if (update == null) throw new IllegalStateException("Missing required property: update");
            return new SessionHandoffProgressNotification(callbackId, update);
        }
    }
}
