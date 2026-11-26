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
 * Read-only state health for hosts that report recovery problems or first-launch status.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = GlobalStateDiagnosticsResult.$NullableDeserializer.class)
record GlobalStateDiagnosticsResult(
    /** Whether the normalized state contains any keys, including keys excluded from SDK reads. */
    @JsonProperty("hasState") Boolean hasState,
    /** State file that JSONC recovery cannot read as an object; absent for missing or readable files. */
    @JsonProperty("unreadablePath") String unreadablePath,
    @com.fasterxml.jackson.annotation.JsonIgnore java.util.Set<String> $explicitNulls
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    GlobalStateDiagnosticsResult { $explicitNulls = $explicitNulls == null ? java.util.Set.of() : java.util.Set.copyOf($explicitNulls); }

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
        private Boolean hasState;
        private String unreadablePath;
        private boolean $presence1;

        /**
         * Sets the {@code hasState} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder hasState(Boolean value) {
            this.hasState = value;
            return this;
        }

        /**
         * Sets the {@code unreadablePath} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder unreadablePath(String value) {
            this.unreadablePath = value;
            this.$presence1 = true;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public GlobalStateDiagnosticsResult build() {
            if (hasState == null) throw new IllegalStateException("Missing required property: hasState");
            java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();
            if ($presence1 && unreadablePath == null) $explicitNulls.add("unreadablePath");
            return new GlobalStateDiagnosticsResult(hasState, unreadablePath, $explicitNulls);
        }
    }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
    public java.util.Map<String, Object> $nullableProperties() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        if ($explicitNulls.contains("unreadablePath") && unreadablePath == null) values.put("unreadablePath", null);
        return values;
    }

    public static final class $NullableDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<GlobalStateDiagnosticsResult> {
        @Override
        public GlobalStateDiagnosticsResult deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode tree = context.readTree(parser);
            if (!tree.isObject()) return context.reportInputMismatch(GlobalStateDiagnosticsResult.class, "Expected a JSON object");
            java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();
            if (tree.has("unreadablePath") && tree.get("unreadablePath").isNull()) $explicitNulls.add("unreadablePath");
            return new GlobalStateDiagnosticsResult(readProperty(context, tree, "hasState", new com.fasterxml.jackson.core.type.TypeReference<Boolean>() {}), readProperty(context, tree, "unreadablePath", new com.fasterxml.jackson.core.type.TypeReference<String>() {}), $explicitNulls);
        }

        private static <T> T readProperty(com.fasterxml.jackson.databind.DeserializationContext context,
                com.fasterxml.jackson.databind.JsonNode tree, String name,
                com.fasterxml.jackson.core.type.TypeReference<T> type) throws java.io.IOException {
            if (!tree.has(name)) return null;
            return context.readTreeAsValue(tree.get(name), context.getTypeFactory().constructType(type));
        }
    }
}
