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
 * Runtime model policy for a host's model, reasoning, and context selectors.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelSelectionPolicyEntry(
    /** Catalog model identifier. */
    @JsonProperty("modelId") String modelId,
    /** Provider identity for provider-qualified model selections. */
    @JsonProperty("providerId") String providerId,
    /** Premium-request multiplier resolved against the complete catalog. */
    @JsonProperty("multiplier") Double multiplier,
    /** Whether this model requires user enablement. */
    @JsonProperty("needsEnablement") Boolean needsEnablement,
    /** Runtime model category, when the catalog supplies one. */
    @JsonProperty("pickerCategory") String pickerCategory,
    /** Runtime price category, when the catalog supplies one. */
    @JsonProperty("priceCategory") String priceCategory,
    /** Reasoning efforts the model currently permits. */
    @JsonProperty("reasoningEfforts") List<String> reasoningEfforts,
    /** Runtime default reasoning effort, when available. */
    @JsonProperty("defaultReasoningEffort") String defaultReasoningEffort,
    /** Selectable context tiers, omitted when the model has no tier control. */
    @JsonProperty("contextTiers") List<ModelContextTierOption> contextTiers,
    /** Registry-preferred context tier, or the default tier when none is preferred. */
    @JsonProperty("defaultContextTier") String defaultContextTier
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public ModelSelectionPolicyEntry {}

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
        private String modelId;
        private String providerId;
        private Double multiplier;
        private Boolean needsEnablement;
        private String pickerCategory;
        private String priceCategory;
        private List<String> reasoningEfforts;
        private String defaultReasoningEffort;
        private List<ModelContextTierOption> contextTiers;
        private String defaultContextTier;

        /**
         * Sets the {@code modelId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder modelId(String value) {
            this.modelId = value;
            return this;
        }

        /**
         * Sets the {@code providerId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder providerId(String value) {
            this.providerId = value;
            return this;
        }

        /**
         * Sets the {@code multiplier} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder multiplier(Double value) {
            this.multiplier = value;
            return this;
        }

        /**
         * Sets the {@code needsEnablement} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder needsEnablement(Boolean value) {
            this.needsEnablement = value;
            return this;
        }

        /**
         * Sets the {@code pickerCategory} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder pickerCategory(String value) {
            this.pickerCategory = value;
            return this;
        }

        /**
         * Sets the {@code priceCategory} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder priceCategory(String value) {
            this.priceCategory = value;
            return this;
        }

        /**
         * Sets the {@code reasoningEfforts} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder reasoningEfforts(List<String> value) {
            this.reasoningEfforts = value;
            return this;
        }

        /**
         * Sets the {@code defaultReasoningEffort} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder defaultReasoningEffort(String value) {
            this.defaultReasoningEffort = value;
            return this;
        }

        /**
         * Sets the {@code contextTiers} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder contextTiers(List<ModelContextTierOption> value) {
            this.contextTiers = value;
            return this;
        }

        /**
         * Sets the {@code defaultContextTier} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder defaultContextTier(String value) {
            this.defaultContextTier = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public ModelSelectionPolicyEntry build() {
            if (modelId == null) throw new IllegalStateException("Missing required property: modelId");
            if (multiplier == null) throw new IllegalStateException("Missing required property: multiplier");
            if (needsEnablement == null) throw new IllegalStateException("Missing required property: needsEnablement");
            if (reasoningEfforts == null) throw new IllegalStateException("Missing required property: reasoningEfforts");
            return new ModelSelectionPolicyEntry(modelId, providerId, multiplier, needsEnablement, pickerCategory, priceCategory, reasoningEfforts, defaultReasoningEffort, contextTiers, defaultContextTier);
        }
    }
}
