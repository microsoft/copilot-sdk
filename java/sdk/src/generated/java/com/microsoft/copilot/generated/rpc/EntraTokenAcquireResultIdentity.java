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
 * Variant {@code identity} of {@link EntraTokenAcquireResult}.
 *
 * @apiNote This type is experimental and may change in a future version.
 *
 * @since 1.0.0
 */
@CopilotExperimental
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public final class EntraTokenAcquireResultIdentity extends EntraTokenAcquireResult {

    @JsonProperty("kind")
    private final String kind = "identity";

    @Override
    public String getKind() { return kind; }

    /** Stable, account-specific opaque host ID, preserved exactly as the suffix of entra:&lt;accountId> and echoed on later requests. Must remain stable across renewal, reauthentication, reconnect and restart. Nonblank, without control characters, and at most 480 UTF-8 bytes; hosts may namespace their IDs. */
    @JsonProperty("accountId")
    private String accountId;

    /** Optional human-readable account label. Never used as account identity. */
    @JsonProperty("username")
    private String username;

    /** Required Entra tenant ID, stored as non-secret parent-account metadata for service routing. Microsoft internal tenants use MSIT Loki; other tenants use production. Does not affect account identity. */
    @JsonProperty("tenantId")
    private String tenantId;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }


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
        private String accountId;
        private String username;
        private String tenantId;

        /**
         * Sets the {@code accountId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder accountId(String value) {
            this.accountId = value;
            return this;
        }

        /**
         * Sets the {@code username} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder username(String value) {
            this.username = value;
            return this;
        }

        /**
         * Sets the {@code tenantId} property.
         * @param value the property value
         * @return this builder
         * @since 2.0.0
         */
        @com.microsoft.copilot.CopilotExperimental
        public Builder tenantId(String value) {
            this.tenantId = value;
            return this;
        }

        /**
         * Builds a variant after checking required property presence.
         * @return a new variant
         * @throws IllegalStateException if a required property is missing
         * @since 2.0.0
         */
        public EntraTokenAcquireResultIdentity build() {
            if (accountId == null) throw new IllegalStateException("Missing required property: accountId");
            if (tenantId == null) throw new IllegalStateException("Missing required property: tenantId");
            EntraTokenAcquireResultIdentity instance = new EntraTokenAcquireResultIdentity();
            instance.setAccountId(accountId);
            instance.setUsername(username);
            instance.setTenantId(tenantId);
            return instance;
        }
    }
}
