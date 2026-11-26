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
 * Requests confirmation before a handoff uses a different local repository.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionHandoffConfirmationRequest(
    /** The callback ID supplied by the handoff requester. */
    @JsonProperty("callbackId") String callbackId,
    /** The owner/name of the local repository. */
    @JsonProperty("localRepo") String localRepo,
    /** The owner/name recorded by the remote session. */
    @JsonProperty("remoteRepo") String remoteRepo
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SessionHandoffConfirmationRequest {}

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
        private String localRepo;
        private String remoteRepo;

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
         * Sets the {@code localRepo} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder localRepo(String value) {
            this.localRepo = value;
            return this;
        }

        /**
         * Sets the {@code remoteRepo} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder remoteRepo(String value) {
            this.remoteRepo = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SessionHandoffConfirmationRequest build() {
            if (callbackId == null) throw new IllegalStateException("Missing required property: callbackId");
            if (localRepo == null) throw new IllegalStateException("Missing required property: localRepo");
            if (remoteRepo == null) throw new IllegalStateException("Missing required property: remoteRepo");
            return new SessionHandoffConfirmationRequest(callbackId, localRepo, remoteRepo);
        }
    }
}
