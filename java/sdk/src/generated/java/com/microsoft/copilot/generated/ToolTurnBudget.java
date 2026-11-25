/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: session-events.schema.json

package com.microsoft.copilot.generated;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.processing.Generated;

@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolTurnBudget(
    /** Maximum logical model turns allowed for this request. */
    @JsonProperty("limit") Long limit,
    /** Logical model turns already admitted for this request. */
    @JsonProperty("used") Long used
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public ToolTurnBudget {}

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
        private Long limit;
        private Long used;

        /**
         * Sets the {@code limit} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder limit(Long value) {
            this.limit = value;
            return this;
        }

        /**
         * Sets the {@code used} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder used(Long value) {
            this.used = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public ToolTurnBudget build() {
            if (limit == null) throw new IllegalStateException("Missing required property: limit");
            if (used == null) throw new IllegalStateException("Missing required property: used");
            return new ToolTurnBudget(limit, used);
        }
    }
}
