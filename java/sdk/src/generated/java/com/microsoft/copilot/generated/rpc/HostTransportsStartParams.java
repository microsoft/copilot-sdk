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
 * Starts a transport on the caller's existing runtime host. Requires private admission of the owning native SDK connection.
 *
 * @apiNote This method is experimental and may change in a future version.
 * @since 1.0.0
 */
@CopilotExperimental
@javax.annotation.processing.Generated("copilot-sdk-codegen")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HostTransportsStartParams(
    /** Protocol to serve. */
    @JsonProperty("protocol") HostTransportProtocol protocol,
    /** TCP listener or process standard streams. */
    @JsonProperty("transport") HostTransportKind transport,
    /** TCP address. Omitted values bind the IPv4 loopback address.
ACP accepts only loopback addresses. Non-loopback JSON-RPC requires a connection token. */
    @JsonProperty("hostname") String hostname,
    /** TCP port. Zero or omission requests an available port. */
    @JsonProperty("port") Long port,
    /** SDK JSON-RPC connection token. Required for non-loopback JSON-RPC listeners; unsupported for ACP. */
    @JsonProperty("connectionToken") String connectionToken,
    /** Required ACP launcher inputs. Omit for JSON-RPC. */
    @JsonProperty("acp") HostAcpTransportOptions acp
) {

    /**
     * Creates a record positionally.
     * Use {@link #builder()} for named construction.
     */
    public HostTransportsStartParams {}

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
        private HostTransportProtocol protocol;
        private HostTransportKind transport;
        private String hostname;
        private Long port;
        private String connectionToken;
        private HostAcpTransportOptions acp;

        /**
         * Sets the {@code protocol} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder protocol(HostTransportProtocol value) {
            this.protocol = value;
            return this;
        }

        /**
         * Sets the {@code transport} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder transport(HostTransportKind value) {
            this.transport = value;
            return this;
        }

        /**
         * Sets the {@code hostname} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder hostname(String value) {
            this.hostname = value;
            return this;
        }

        /**
         * Sets the {@code port} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder port(Long value) {
            this.port = value;
            return this;
        }

        /**
         * Sets the {@code connectionToken} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder connectionToken(String value) {
            this.connectionToken = value;
            return this;
        }

        /**
         * Sets the {@code acp} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder acp(HostAcpTransportOptions value) {
            this.acp = value;
            return this;
        }

        /**
         * Builds a record after checking required property presence.
         * @return a new record
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public HostTransportsStartParams build() {
            if (protocol == null) throw new IllegalStateException("Missing required property: protocol");
            if (transport == null) throw new IllegalStateException("Missing required property: transport");
            return new HostTransportsStartParams(protocol, transport, hostname, port, connectionToken, acp);
        }
    }
}
