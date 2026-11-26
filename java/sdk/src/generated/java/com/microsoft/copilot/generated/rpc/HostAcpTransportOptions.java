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
import java.util.Map;
import javax.annotation.processing.Generated;

/**
 * ACP host inputs. These values belong to the embedding application, not an incoming ACP client.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HostAcpTransportOptions(
    /** Directory for materialized ACP prompt attachments. */
    @JsonProperty("tempDirectory") String tempDirectory,
    /** Agent version announced during ACP initialization. */
    @JsonProperty("agentVersion") String agentVersion,
    /** Program advertised for interactive authentication. */
    @JsonProperty("loginCommand") String loginCommand,
    /** Arguments advertised for interactive authentication. */
    @JsonProperty("loginArgs") List<String> loginArgs,
    /** Host-resolved session defaults, with the same authority as ACP launcher options. */
    @JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.ALWAYS)
    @JsonProperty("sessionDefaults") Map<String, Object> sessionDefaults
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public HostAcpTransportOptions {}

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
        private String tempDirectory;
        private String agentVersion;
        private String loginCommand;
        private List<String> loginArgs;
        private Map<String, Object> sessionDefaults;

        /**
         * Sets the {@code tempDirectory} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder tempDirectory(String value) {
            this.tempDirectory = value;
            return this;
        }

        /**
         * Sets the {@code agentVersion} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder agentVersion(String value) {
            this.agentVersion = value;
            return this;
        }

        /**
         * Sets the {@code loginCommand} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder loginCommand(String value) {
            this.loginCommand = value;
            return this;
        }

        /**
         * Sets the {@code loginArgs} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder loginArgs(List<String> value) {
            this.loginArgs = value;
            return this;
        }

        /**
         * Sets the {@code sessionDefaults} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder sessionDefaults(Map<String, Object> value) {
            this.sessionDefaults = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public HostAcpTransportOptions build() {
            if (tempDirectory == null) throw new IllegalStateException("Missing required property: tempDirectory");
            if (agentVersion == null) throw new IllegalStateException("Missing required property: agentVersion");
            if (loginCommand == null) throw new IllegalStateException("Missing required property: loginCommand");
            if (loginArgs == null) throw new IllegalStateException("Missing required property: loginArgs");
            return new HostAcpTransportOptions(tempDirectory, agentVersion, loginCommand, loginArgs, sessionDefaults);
        }
    }
}
