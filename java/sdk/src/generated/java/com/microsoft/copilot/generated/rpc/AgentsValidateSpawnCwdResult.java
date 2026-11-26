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
 * Working directory validation outcome. All stat errors produce cwd-not-found.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentsValidateSpawnCwdResult(
    /** Whether the path exists and is a directory. */
    @JsonProperty("ok") Boolean ok,
    /** Rejection category, absent on success. */
    @JsonProperty("reason") SpawnCwdValidationReason reason,
    /** Display message, absent on success. */
    @JsonProperty("message") String message
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public AgentsValidateSpawnCwdResult {}

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
        private Boolean ok;
        private SpawnCwdValidationReason reason;
        private String message;

        /**
         * Sets the {@code ok} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder ok(Boolean value) {
            this.ok = value;
            return this;
        }

        /**
         * Sets the {@code reason} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder reason(SpawnCwdValidationReason value) {
            this.reason = value;
            return this;
        }

        /**
         * Sets the {@code message} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder message(String value) {
            this.message = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public AgentsValidateSpawnCwdResult build() {
            if (ok == null) throw new IllegalStateException("Missing required property: ok");
            return new AgentsValidateSpawnCwdResult(ok, reason, message);
        }
    }
}
