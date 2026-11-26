/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

// AUTO-GENERATED FILE - DO NOT EDIT
// Generated from: api.schema.json

package com.microsoft.copilot.generated.rpc;

import com.microsoft.copilot.CopilotExperimental;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.processing.Generated;

/**
 * API methods for the {@code mcp.config} namespace.
 *
 * @since 1.0.0
 */
@javax.annotation.processing.Generated("copilot-sdk-codegen")
public final class ServerMcpConfigApi {

    private final RpcCaller caller;

    /** @param caller the RPC transport function */
    ServerMcpConfigApi(RpcCaller caller) {
        this.caller = caller;
    }

    /**
     * Lists MCP servers from user configuration.
     * <p>
     * Invokes the method with no params, applying the runtime defaults.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<McpConfigListResult> list() {
        return list(null);
    }

    /**
     * Lists MCP servers from user configuration.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<McpConfigListResult> list(McpConfigListParams params) {
        return caller.invoke("mcp.config.list", params == null ? java.util.Map.of() : params, McpConfigListResult.class);
    }

    /**
     * Adds an MCP server to user configuration.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> add(McpConfigAddParams params) {
        return caller.invoke("mcp.config.add", params, Void.class);
    }

    /**
     * Adds an MCP server to user configuration.
     * <p>
     * Accepts the extensible request, including inputs added after the params record.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> add(McpConfigAddRequest request) {
        return caller.invoke("mcp.config.add", Objects.requireNonNull(request, "request"), Void.class);
    }

    /**
     * Reads a persisted MCP document or fresh raw user membership from the selected MCP configuration root. Uses a separate result to preserve existing normalized list response construction in all SDKs.
     * <p>
     * Invokes the method with no params, applying the runtime defaults.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<McpConfigReadResult> read() {
        return read(null);
    }

    /**
     * Reads a persisted MCP document or fresh raw user membership from the selected MCP configuration root. Uses a separate result to preserve existing normalized list response construction in all SDKs.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<McpConfigReadResult> read(McpConfigReadParams params) {
        return caller.invoke("mcp.config.read", params == null ? java.util.Map.of() : params, McpConfigReadResult.class);
    }

    /**
     * Writes a complete persisted MCP configuration document atomically under the existing installation ownership lease and updates its validated cache. Unlike per-server mutations, preserves document-level fields and bulk replacement semantics.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<Void> write(McpConfigWriteParams params) {
        return caller.invoke("mcp.config.write", params, Void.class);
    }

    /**
     * Lists regular JSON files and their modification and birth times in the selected MCP configuration directory, using the existing platform metadata and ordering rules.
     * <p>
     * Invokes the method with no params, applying the runtime defaults.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<McpConfigFilesResult> files() {
        return files(null);
    }

    /**
     * Lists regular JSON files and their modification and birth times in the selected MCP configuration directory, using the existing platform metadata and ordering rules.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<McpConfigFilesResult> files(McpConfigFilesParams params) {
        return caller.invoke("mcp.config.files", params == null ? java.util.Map.of() : params, McpConfigFilesResult.class);
    }

    /**
     * Updates an MCP server in user configuration.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> update(McpConfigUpdateParams params) {
        return caller.invoke("mcp.config.update", params, Void.class);
    }

    /**
     * Removes an MCP server from user configuration.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> remove(McpConfigRemoveParams params) {
        return caller.invoke("mcp.config.remove", params, Void.class);
    }

    /**
     * Removes an MCP server from user configuration.
     * <p>
     * Accepts the extensible request, including inputs added after the params record.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> remove(McpConfigRemoveRequest request) {
        return caller.invoke("mcp.config.remove", Objects.requireNonNull(request, "request"), Void.class);
    }

    /**
     * Enables MCP servers in user configuration for new sessions.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> enable(McpConfigEnableParams params) {
        return caller.invoke("mcp.config.enable", params, Void.class);
    }

    /**
     * Enables MCP servers in user configuration for new sessions.
     * <p>
     * Accepts the extensible request, including inputs added after the params record.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> enable(McpConfigEnableRequest request) {
        return caller.invoke("mcp.config.enable", Objects.requireNonNull(request, "request"), Void.class);
    }

    /**
     * Disables MCP servers in user configuration for new sessions.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> disable(McpConfigDisableParams params) {
        return caller.invoke("mcp.config.disable", params, Void.class);
    }

    /**
     * Disables MCP servers in user configuration for new sessions.
     * <p>
     * Accepts the extensible request, including inputs added after the params record.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> disable(McpConfigDisableRequest request) {
        return caller.invoke("mcp.config.disable", Objects.requireNonNull(request, "request"), Void.class);
    }

    /**
     * Drops this runtime process's in-memory MCP server-definition cache so the next MCP config read observes disk.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    public CompletableFuture<Void> reload() {
        return caller.invoke("mcp.config.reload", java.util.Map.of(), Void.class);
    }

    /**
     * Validates and composes additional MCP documents in precedence order without modifying persisted configuration.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<McpConfigComposeAdditionalResult> composeAdditional(McpConfigComposeAdditionalParams params) {
        return caller.invoke("mcp.config.composeAdditional", params, McpConfigComposeAdditionalResult.class);
    }

    /**
     * Reads fresh persisted user MCP server names under an explicit configuration root, including invalid entries but excluding installation-owned entries. Leaves the validated cache unchanged and requires a trusted host or direct SDK caller.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<McpConfigUserServerNamesResult> userServerNamesForConfigDir(McpConfigUserServerNamesForConfigDirParams params) {
        return caller.invoke("mcp.config.userServerNamesForConfigDir", params, McpConfigUserServerNamesResult.class);
    }

    /**
     * Composes explicit MCP source inventories through the shared loader, including trusted workspace and on-device sources. This read-only result grants no execution authority.
     *
     * @apiNote This method is experimental and may change in a future version.
     * @since 1.0.0
     */
    @CopilotExperimental
    CompletableFuture<McpConfigMergeSourcesResult> mergeSources(McpConfigMergeSourcesParams params) {
        return caller.invoke("mcp.config.mergeSources", params, McpConfigMergeSourcesResult.class);
    }

}
