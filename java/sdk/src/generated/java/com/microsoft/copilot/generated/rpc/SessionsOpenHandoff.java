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
 * Parameters for fetching a remote session and handing it off to a new local session.
 *
 * @since 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public final class SessionsOpenHandoff extends SessionsOpenParams {

    @JsonProperty("kind")
    private final String kind = "handoff";

    @Override
    public String getKind() { return kind; }

    /** Remote session metadata for the session to hand off (typically obtained from `sessions.list` with `source: "remote"`). */
    @JsonProperty("metadata")
    private RemoteSessionMetadataValue metadata;

    /** Session construction options for the new local session. */
    @JsonProperty("options")
    private SessionOpenOptions options;

    /** Task type determines the handoff strategy (CCA fetches events; CLI prepares a transient session). */
    @JsonProperty("taskType")
    private SessionsOpenHandoffTaskType taskType;

    /** Correlates optional progress and confirmation callbacks on the requesting SDK connection. */
    @JsonProperty("callbackId")
    private String callbackId;

    /** Legacy in-process progress callback. SDK connections use callbackId and sessionHandoff.progress notifications instead; function references cannot cross JSON-RPC. */
    @JsonProperty("onProgress")
    private Object onProgress;

    /** Legacy in-process confirmation callback. SDK connections use callbackId and sessionHandoff.confirm requests instead; missing or declined confirmation refuses a repository mismatch. */
    @JsonProperty("onConfirm")
    private Object onConfirm;

    public RemoteSessionMetadataValue getMetadata() { return metadata; }
    public void setMetadata(RemoteSessionMetadataValue metadata) { this.metadata = metadata; }

    public SessionOpenOptions getOptions() { return options; }
    public void setOptions(SessionOpenOptions options) { this.options = options; }

    public SessionsOpenHandoffTaskType getTaskType() { return taskType; }
    public void setTaskType(SessionsOpenHandoffTaskType taskType) { this.taskType = taskType; }

    public String getCallbackId() { return callbackId; }
    public void setCallbackId(String callbackId) { this.callbackId = callbackId; }

    public Object getOnProgress() { return onProgress; }
    public void setOnProgress(Object onProgress) { this.onProgress = onProgress; }

    public Object getOnConfirm() { return onConfirm; }
    public void setOnConfirm(Object onConfirm) { this.onConfirm = onConfirm; }


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
        private RemoteSessionMetadataValue metadata;
        private SessionOpenOptions options;
        private SessionsOpenHandoffTaskType taskType;
        private String callbackId;
        private Object onProgress;
        private Object onConfirm;

        /**
         * Sets the {@code metadata} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder metadata(RemoteSessionMetadataValue value) {
            this.metadata = value;
            return this;
        }

        /**
         * Sets the {@code options} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder options(SessionOpenOptions value) {
            this.options = value;
            return this;
        }

        /**
         * Sets the {@code taskType} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder taskType(SessionsOpenHandoffTaskType value) {
            this.taskType = value;
            return this;
        }

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
         * Sets the {@code onProgress} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder onProgress(Object value) {
            this.onProgress = value;
            return this;
        }

        /**
         * Sets the {@code onConfirm} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder onConfirm(Object value) {
            this.onConfirm = value;
            return this;
        }

        /**
         * Builds a variant after checking required property presence.
         * @return a new variant
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SessionsOpenHandoff build() {
            if (metadata == null) throw new IllegalStateException("Missing required property: metadata");
            SessionsOpenHandoff instance = new SessionsOpenHandoff();
            instance.setMetadata(metadata);
            instance.setOptions(options);
            instance.setTaskType(taskType);
            instance.setCallbackId(callbackId);
            instance.setOnProgress(onProgress);
            instance.setOnConfirm(onConfirm);
            return instance;
        }
    }
}
