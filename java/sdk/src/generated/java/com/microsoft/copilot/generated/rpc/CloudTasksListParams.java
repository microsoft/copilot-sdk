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
 * Lists Mission Control task metadata under an explicit account, preserving numbered pagination.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record CloudTasksListParams(
    /** Account whose remote tasks are listed. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonProperty("authInfo") Object authInfo,
    /** Retained credential context for the account, when supplied by the host. */
    @JsonProperty("authContextId") String authContextId,
    /** Embedding client attribution sent to Mission Control. */
    @JsonProperty("integrationId") String integrationId,
    /** Include archived tasks as well as active tasks. */
    @JsonProperty("includeArchived") Boolean includeArchived,
    /** Maximum tasks requested per service page. */
    @JsonProperty("limit") Double limit,
    /** Fetch all numbered pages instead of only the first page. */
    @JsonProperty("allPages") Boolean allPages,
    /** Positive numbered page to fetch; cannot be combined with allPages. */
    @JsonProperty("page") Long page,
    /** Maximum raw records to read before filtering and deduplication. */
    @JsonProperty("maxRecords") Long maxRecords,
    /** Preserve all tasks instead of only resumable remote sessions. */
    @JsonProperty("taskCatalog") Boolean taskCatalog,
    /** GitHub repository-enrichment endpoint override. */
    @JsonProperty("debugGithubApiUrl") String debugGitHubApiUrl,
    /** Overall listing deadline in milliseconds. */
    @JsonProperty("timeoutMs") Double timeoutMs
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    CloudTasksListParams {}

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
        private Object authInfo;
        private boolean $presence0;
        private String authContextId;
        private String integrationId;
        private Boolean includeArchived;
        private Double limit;
        private Boolean allPages;
        private Long page;
        private Long maxRecords;
        private Boolean taskCatalog;
        private String debugGitHubApiUrl;
        private Double timeoutMs;

        /**
         * Sets the {@code authInfo} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder authInfo(Object value) {
            this.authInfo = value;
            this.$presence0 = true;
            return this;
        }

        /**
         * Sets the {@code authContextId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder authContextId(String value) {
            this.authContextId = value;
            return this;
        }

        /**
         * Sets the {@code integrationId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder integrationId(String value) {
            this.integrationId = value;
            return this;
        }

        /**
         * Sets the {@code includeArchived} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder includeArchived(Boolean value) {
            this.includeArchived = value;
            return this;
        }

        /**
         * Sets the {@code limit} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder limit(Double value) {
            this.limit = value;
            return this;
        }

        /**
         * Sets the {@code allPages} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder allPages(Boolean value) {
            this.allPages = value;
            return this;
        }

        /**
         * Sets the {@code page} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder page(Long value) {
            this.page = value;
            return this;
        }

        /**
         * Sets the {@code maxRecords} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder maxRecords(Long value) {
            this.maxRecords = value;
            return this;
        }

        /**
         * Sets the {@code taskCatalog} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder taskCatalog(Boolean value) {
            this.taskCatalog = value;
            return this;
        }

        /**
         * Sets the {@code debugGithubApiUrl} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder debugGitHubApiUrl(String value) {
            this.debugGitHubApiUrl = value;
            return this;
        }

        /**
         * Sets the {@code timeoutMs} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder timeoutMs(Double value) {
            this.timeoutMs = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public CloudTasksListParams build() {
            if (!$presence0) throw new IllegalStateException("Missing required property: authInfo");
            if (integrationId == null) throw new IllegalStateException("Missing required property: integrationId");
            if (includeArchived == null) throw new IllegalStateException("Missing required property: includeArchived");
            if (limit == null) throw new IllegalStateException("Missing required property: limit");
            if (allPages == null) throw new IllegalStateException("Missing required property: allPages");
            if (taskCatalog == null) throw new IllegalStateException("Missing required property: taskCatalog");
            if (timeoutMs == null) throw new IllegalStateException("Missing required property: timeoutMs");
            return new CloudTasksListParams(authInfo, authContextId, integrationId, includeArchived, limit, allPages, page, maxRecords, taskCatalog, debugGitHubApiUrl, timeoutMs);
        }
    }
}
