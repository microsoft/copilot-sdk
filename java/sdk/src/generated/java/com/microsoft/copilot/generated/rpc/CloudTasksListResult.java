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
 * Remote task metadata and pagination, including structured HTTP or partial-archive failures.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record CloudTasksListResult(
    /** Whether the active task listing succeeded. */
    @JsonProperty("ok") Boolean ok,
    /** Whether raw task pages indicate more records may remain. */
    @JsonProperty("hasMore") Boolean hasMore,
    /** Raw task records read before filtering and deduplication. */
    @JsonProperty("recordCount") Long recordCount,
    /** Server-requested cooldown in milliseconds. */
    @JsonProperty("retryAfterMs") Double retryAfterMs,
    /** JSON array of remote session metadata on success. */
    @JsonProperty("sessionsJson") String sessionsJson,
    /** Archived-task failure accompanying a successful active listing. */
    @JsonProperty("archivedFailure") String archivedFailure,
    /** Whether the overall listing deadline expired. */
    @JsonProperty("timedOut") Boolean timedOut,
    /** HTTP status for a failed listing. */
    @JsonProperty("statusCode") Long statusCode,
    /** Explanation of a failed listing. */
    @JsonProperty("errorMessage") String errorMessage,
    /** Service failure classification. */
    @JsonProperty("errorKind") String errorKind,
    /** Original service error payload serialized as JSON. */
    @JsonProperty("errorPayloadJson") String errorPayloadJson
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    CloudTasksListResult {}

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
        private Boolean ok;
        private Boolean hasMore;
        private Long recordCount;
        private Double retryAfterMs;
        private String sessionsJson;
        private String archivedFailure;
        private Boolean timedOut;
        private Long statusCode;
        private String errorMessage;
        private String errorKind;
        private String errorPayloadJson;

        /**
         * Sets the {@code ok} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder ok(Boolean value) {
            this.ok = value;
            return this;
        }

        /**
         * Sets the {@code hasMore} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder hasMore(Boolean value) {
            this.hasMore = value;
            return this;
        }

        /**
         * Sets the {@code recordCount} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder recordCount(Long value) {
            this.recordCount = value;
            return this;
        }

        /**
         * Sets the {@code retryAfterMs} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder retryAfterMs(Double value) {
            this.retryAfterMs = value;
            return this;
        }

        /**
         * Sets the {@code sessionsJson} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder sessionsJson(String value) {
            this.sessionsJson = value;
            return this;
        }

        /**
         * Sets the {@code archivedFailure} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder archivedFailure(String value) {
            this.archivedFailure = value;
            return this;
        }

        /**
         * Sets the {@code timedOut} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder timedOut(Boolean value) {
            this.timedOut = value;
            return this;
        }

        /**
         * Sets the {@code statusCode} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder statusCode(Long value) {
            this.statusCode = value;
            return this;
        }

        /**
         * Sets the {@code errorMessage} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder errorMessage(String value) {
            this.errorMessage = value;
            return this;
        }

        /**
         * Sets the {@code errorKind} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder errorKind(String value) {
            this.errorKind = value;
            return this;
        }

        /**
         * Sets the {@code errorPayloadJson} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder errorPayloadJson(String value) {
            this.errorPayloadJson = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public CloudTasksListResult build() {
            if (ok == null) throw new IllegalStateException("Missing required property: ok");
            if (hasMore == null) throw new IllegalStateException("Missing required property: hasMore");
            if (recordCount == null) throw new IllegalStateException("Missing required property: recordCount");
            if (timedOut == null) throw new IllegalStateException("Missing required property: timedOut");
            return new CloudTasksListResult(ok, hasMore, recordCount, retryAfterMs, sessionsJson, archivedFailure, timedOut, statusCode, errorMessage, errorKind, errorPayloadJson);
        }
    }
}
