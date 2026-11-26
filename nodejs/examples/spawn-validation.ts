/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

import {
    CopilotClient,
    RuntimeConnection,
    type AgentsValidateSpawnAgentNameRequest,
} from "@microsoft/copilot-sdk";

const path = process.argv[2];
if (!path) {
    throw new Error("Usage: node --import tsx examples/spawn-validation.ts <copilot-runtime-path>");
}

const client = new CopilotClient({
    mode: "coding-agent",
    runtime: RuntimeConnection.forSpawnedStdio({ path }),
    github: { useLoggedInUser: false },
});
try {
    await client.start();
    // An IDE supplies the identifiers visible in its agent picker before spawn.
    console.log(
        "Agent:",
        await client.rpc.agents.validateSpawnAgentName({
            name: "plugin:reviewer",
            customAgents: [{ name: "reviewer", id: "plugin/reviewer" }],
            builtinNames: [],
        } satisfies AgentsValidateSpawnAgentNameRequest)
    );
    console.log("Directory:", await client.rpc.agents.validateSpawnCwd({ cwd: process.cwd() }));
    console.log(
        "Unknown agent:",
        await client.rpc.agents.validateSpawnAgentName({
            name: "missing",
            customAgents: [],
            builtinNames: ["explore"],
        })
    );
} finally {
    await client.stop();
}
