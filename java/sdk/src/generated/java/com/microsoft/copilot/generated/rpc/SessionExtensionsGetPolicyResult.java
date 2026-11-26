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
 * Runtime support and session-context standalone executable extension policy.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = SessionExtensionsGetPolicyResult.$NullableDeserializer.class)
public record SessionExtensionsGetPolicyResult(
    /** Source categories permitted by the effective managed extension policy. This report does not include user enablement, session source selection, plugin activation, discovered entries, or launch approval. Null means managed policy is unresolved. Omission means the runtime does not support this report. */
    @JsonProperty("managedAllowedSources") List<ManagedExtensionSource> managedAllowedSources,
    /** Whether standalone extensions are allowed, prohibited by plugin-only policy, or pending unresolved managed policy. */
    @JsonProperty("standalone") StandaloneExtensionAdmission standalone,
    /** Extension policy enforcement contract version. Version 1 includes standalone discovery, launch admission, and live-policy revocation. */
    @JsonProperty("enforcementVersion") Long enforcementVersion,
    /** Executable entries discovered from this session's runtime-verified eligible plugins. Under plugin_only, independently launched targets must match these entry paths; an external package, a caller-supplied plugin label, or a synthetic transport bootstrap does not establish eligibility. Empty while pending or when no extension controller is configured. */
    @JsonProperty("eligiblePluginEntries") List<EligiblePluginExtensionEntry> eligiblePluginEntries,
    @com.fasterxml.jackson.annotation.JsonIgnore java.util.Set<String> $explicitNulls
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public SessionExtensionsGetPolicyResult { $explicitNulls = $explicitNulls == null ? java.util.Set.of() : java.util.Set.copyOf($explicitNulls); }

    /**
     * Creates a record without optional runtime metadata.
     * @param standalone the {@code standalone} property
     * @param enforcementVersion the {@code enforcementVersion} property
     * @param eligiblePluginEntries the {@code eligiblePluginEntries} property
     */
    public SessionExtensionsGetPolicyResult(StandaloneExtensionAdmission standalone, Long enforcementVersion, List<EligiblePluginExtensionEntry> eligiblePluginEntries) {
        this(null, standalone, enforcementVersion, eligiblePluginEntries, java.util.Set.of());
    }

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
        private List<ManagedExtensionSource> managedAllowedSources;
        private boolean $presence0;
        private StandaloneExtensionAdmission standalone;
        private Long enforcementVersion;
        private List<EligiblePluginExtensionEntry> eligiblePluginEntries;

        /**
         * Sets the {@code managedAllowedSources} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder managedAllowedSources(List<ManagedExtensionSource> value) {
            this.managedAllowedSources = value;
            this.$presence0 = true;
            return this;
        }

        /**
         * Sets the {@code standalone} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder standalone(StandaloneExtensionAdmission value) {
            this.standalone = value;
            return this;
        }

        /**
         * Sets the {@code enforcementVersion} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder enforcementVersion(Long value) {
            this.enforcementVersion = value;
            return this;
        }

        /**
         * Sets the {@code eligiblePluginEntries} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder eligiblePluginEntries(List<EligiblePluginExtensionEntry> value) {
            this.eligiblePluginEntries = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public SessionExtensionsGetPolicyResult build() {
            if (standalone == null) throw new IllegalStateException("Missing required property: standalone");
            if (enforcementVersion == null) throw new IllegalStateException("Missing required property: enforcementVersion");
            if (eligiblePluginEntries == null) throw new IllegalStateException("Missing required property: eligiblePluginEntries");
            java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();
            if ($presence0 && managedAllowedSources == null) $explicitNulls.add("managedAllowedSources");
            return new SessionExtensionsGetPolicyResult(managedAllowedSources, standalone, enforcementVersion, eligiblePluginEntries, $explicitNulls);
        }
    }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
    public java.util.Map<String, Object> $nullableProperties() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        if ($explicitNulls.contains("managedAllowedSources") && managedAllowedSources == null) values.put("managedAllowedSources", null);
        return values;
    }

    public static final class $NullableDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<SessionExtensionsGetPolicyResult> {
        @Override
        public SessionExtensionsGetPolicyResult deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode tree = context.readTree(parser);
            if (!tree.isObject()) return context.reportInputMismatch(SessionExtensionsGetPolicyResult.class, "Expected a JSON object");
            java.util.Set<String> $explicitNulls = new java.util.LinkedHashSet<>();
            if (tree.has("managedAllowedSources") && tree.get("managedAllowedSources").isNull()) $explicitNulls.add("managedAllowedSources");
            return new SessionExtensionsGetPolicyResult(readProperty(context, tree, "managedAllowedSources", new com.fasterxml.jackson.core.type.TypeReference<List<ManagedExtensionSource>>() {}), readProperty(context, tree, "standalone", new com.fasterxml.jackson.core.type.TypeReference<StandaloneExtensionAdmission>() {}), readProperty(context, tree, "enforcementVersion", new com.fasterxml.jackson.core.type.TypeReference<Long>() {}), readProperty(context, tree, "eligiblePluginEntries", new com.fasterxml.jackson.core.type.TypeReference<List<EligiblePluginExtensionEntry>>() {}), $explicitNulls);
        }

        private static <T> T readProperty(com.fasterxml.jackson.databind.DeserializationContext context,
                com.fasterxml.jackson.databind.JsonNode tree, String name,
                com.fasterxml.jackson.core.type.TypeReference<T> type) throws java.io.IOException {
            if (!tree.has(name)) return null;
            return context.readTreeAsValue(tree.get(name), context.getTypeFactory().constructType(type));
        }
    }
}
