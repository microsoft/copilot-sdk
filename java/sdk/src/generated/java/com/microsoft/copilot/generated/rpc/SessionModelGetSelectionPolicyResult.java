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
import java.util.List;
import javax.annotation.processing.Generated;

/**
 * Policy from cached and registered models without a network request.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionModelGetSelectionPolicyResult(
    /** Whether this session has a loaded cache or registered catalog models. */
    @JsonProperty("ready") Boolean ready,
    /** Policy entries in cached catalog order, followed by additional registered models. */
    @JsonProperty("models") List<ModelSelectionPolicyEntry> models
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SessionModelGetSelectionPolicyResult {}

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
        private Boolean ready;
        private List<ModelSelectionPolicyEntry> models;

        /**
         * Sets the {@code ready} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder ready(Boolean value) {
            this.ready = value;
            return this;
        }

        /**
         * Sets the {@code models} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder models(List<ModelSelectionPolicyEntry> value) {
            this.models = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SessionModelGetSelectionPolicyResult build() {
            if (ready == null) throw new IllegalStateException("Missing required property: ready");
            if (models == null) throw new IllegalStateException("Missing required property: models");
            return new SessionModelGetSelectionPolicyResult(ready, models);
        }
    }
}
