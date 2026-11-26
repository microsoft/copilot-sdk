/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

import type {
    CopilotClient,
    CopilotSession,
    LocalSkillDefinitionList,
    SkillsInstallSourceRequest,
    SkillsInstallSourceResult,
    SkillsRemoveSourceRequest,
    SkillsRemoveSourceOutcome,
    SkillsLoadRequest,
    SkillsLoadResult,
    SkillsCacheGeneration,
    PluginsScanDirectoriesResult,
    McpServersConfig,
} from "@microsoft/copilot-sdk";

/** Inspect the session's local definitions without a second policy ingestion. */
export async function inspectSessionSkillSources(
    session: CopilotSession
): Promise<LocalSkillDefinitionList> {
    const result = await session.rpc.skills.getDefinitions();
    console.log(`${result.skills.length} session definitions; ${result.errors.length} errors`);
    return result;
}

/** Preview mounted plugins and their MCP configuration without starting servers. */
export async function inspectPluginSources(
    client: CopilotClient,
    paths: string[],
    workingDirectory: string
): Promise<{ scan: PluginsScanDirectoriesResult; mcp: McpServersConfig }> {
    const scan = await client.rpc.plugins.scanDirectories({ paths, workingDirectory });
    const mcp = await client.rpc.mcp.discoverPluginServers({ installedPlugins: scan.plugins });
    console.log(
        `${scan.plugins.length} plugins; ${Object.keys(mcp.mcpServers ?? {}).length} MCP servers`
    );
    return { scan, mcp };
}

/** Inspect complete definitions through runtime-owned discovery and cache validation. */
export async function inspectSkillSources(
    client: CopilotClient,
    request: SkillsLoadRequest
): Promise<SkillsLoadResult> {
    const result = await client.rpc.skills.load({ ...request, checkSources: true });
    console.log(`${result.skills.length} definitions (generation ${result.cacheGeneration})`);
    for (const warning of result.configurationWarnings ?? []) {
        console.log(`Configuration warning: ${warning}`);
    }
    for (const skill of result.skills) {
        console.log(`${skill.invocationName ?? skill.name}: ${skill.filePath}`);
    }
    return result;
}

/** Invalidate parsed definitions and inspect the runtime cache generation. */
export async function invalidateSkillSources(
    client: CopilotClient
): Promise<SkillsCacheGeneration> {
    const before = await client.rpc.skills.getCacheGeneration();
    const after = await client.rpc.skills.invalidate();
    console.log(`Skill cache generation ${before.generation} -> ${after.generation}`);
    return after;
}

/** Install direct HTTPS/file sources or register discovery directories without catalog plans. */
export async function installSkillSources(
    client: CopilotClient,
    sources: readonly string[],
    destination: Omit<SkillsInstallSourceRequest, "source">
): Promise<SkillsInstallSourceResult[]> {
    let skillDirectories = [...destination.skillDirectories];
    const results: SkillsInstallSourceResult[] = [];
    for (const source of sources) {
        const installed = await client.rpc.skills.installSource({
            ...destination,
            skillDirectories,
            source,
        });
        skillDirectories = installed.skillDirectories;
        results.push(installed);
        console.log(`${installed.kind}: ${installed.path}`);
    }
    return results;
}

/** Unregister a directory or remove a direct file with the caller's discovery inventory. */
export async function removeSkillSource(
    client: CopilotClient,
    request: SkillsRemoveSourceRequest
): Promise<SkillsRemoveSourceOutcome> {
    const outcome = await client.rpc.skills.removeSource(request);
    console.log(
        outcome.kind === "removed"
            ? `${outcome.result.kind}: removed ${outcome.result.path}`
            : "needs-inventory: no files changed"
    );
    return outcome;
}
