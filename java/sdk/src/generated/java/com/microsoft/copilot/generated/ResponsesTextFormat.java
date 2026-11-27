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

/**
 * Original request-level and effective output format for a provider history boundary, in the session.send responseFormat shape; an absent format is plain text
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ResponsesTextFormat.$NullableDeserializer.class)
public record ResponsesTextFormat(
    /** Original request-level output format, retained while replaying this conversation prefix */
    @JsonProperty("initialFormat") Object initialFormat,
    /** Effective output format selected before this message, independent of the request-level format */
    @JsonProperty("format") Object format,
    @com.fasterxml.jackson.annotation.JsonIgnore java.util.Set<String> $explicitNulls
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public ResponsesTextFormat { $explicitNulls = $explicitNulls == null ? java.util.Set.of() : java.util.Set.copyOf($explicitNulls); }

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
        private Object initialFormat;
        private boolean $presence0;
        private Object format;
        private boolean $presence1;

        /**
         * Sets the {@code initialFormat} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder initialFormat(Object value) {
            this.initialFormat = value;
            this.$presence0 = true;
            return this;
        }

        /**
         * Sets the {@code format} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        public Builder format(Object value) {
            this.format = value;
            this.$presence1 = true;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public ResponsesTextFormat build() {
            java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();
            if ($presence0 && initialFormat == null) $explicitNulls.add("initialFormat");
            if ($presence1 && format == null) $explicitNulls.add("format");
            return new ResponsesTextFormat(initialFormat, format, $explicitNulls);
        }
    }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
    public java.util.Map<String, Object> $nullableProperties() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        if ($explicitNulls.contains("initialFormat") && initialFormat == null) values.put("initialFormat", null);
        if ($explicitNulls.contains("format") && format == null) values.put("format", null);
        return values;
    }

    public static final class $NullableDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<ResponsesTextFormat> {
        @Override
        public ResponsesTextFormat deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode tree = context.readTree(parser);
            if (!tree.isObject()) return context.reportInputMismatch(ResponsesTextFormat.class, "Expected a JSON object");
            java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();
            if (tree.has("initialFormat") && tree.get("initialFormat").isNull()) $explicitNulls.add("initialFormat");
            if (tree.has("format") && tree.get("format").isNull()) $explicitNulls.add("format");
            return new ResponsesTextFormat(readProperty(context, tree, "initialFormat", new com.fasterxml.jackson.core.type.TypeReference<Object>() {}), readProperty(context, tree, "format", new com.fasterxml.jackson.core.type.TypeReference<Object>() {}), $explicitNulls);
        }

        private static <T> T readProperty(com.fasterxml.jackson.databind.DeserializationContext context,
                com.fasterxml.jackson.databind.JsonNode tree, String name,
                com.fasterxml.jackson.core.type.TypeReference<T> type) throws java.io.IOException {
            if (!tree.has(name)) return null;
            return context.readTreeAsValue(tree.get(name), context.getTypeFactory().constructType(type));
        }
    }
}
