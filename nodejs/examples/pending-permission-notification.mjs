/* Copyright (c) Microsoft Corporation. All rights reserved. */

// Run with the matching checkout's runtime: node src/sdk/nodejs/examples/pending-permission-notification.mjs /path/to/copilot-runtime
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { join, resolve } from "node:path";
import { approveAll, CopilotClient, RuntimeConnection } from "@microsoft/copilot-sdk";

const runtimePath = process.argv[2];
if (!runtimePath) {
    throw new Error("Usage: node pending-permission-notification.mjs <same-checkout copilot-runtime path>");
}
const home = await mkdtemp(resolve(".copilot-pending-permission-example-"));
const client = new CopilotClient({
    mode: "coding-agent",
    runtime: RuntimeConnection.forSpawnedStdio({
        path: resolve(runtimePath),
        env: { ...process.env, COPILOT_HOME: join(home, "profile") },
    }),
    github: { useLoggedInUser: false },
});
try {
    const session = await client.createSession({
        workingDirectory: home,
        model: "example/example-model",
        inference: {
            providers: [{
                name: "example", type: "openai", baseUrl: "http://127.0.0.1:1", apiKey: "example",
            }],
            models: [{ provider: "example", id: "example-model" }],
        },
        github: { capi: { enabled: false } },
        permissions: { onRequest: approveAll },
    });
    // Existing hosts can still report their own rendered prompt with its message.
    const legacy = await session.rpc.permissions.notifyPromptShown({ message: "Permission needed" });
    // A runtime-owned prompt uses its pending request ID, never a caller's replacement message.
    // A stale ID must not fire a hook for the current session.
    const stale = await session.rpc.permissions.notifyPendingPromptShown({ requestId: "retired-request" });
    assert.equal(legacy.success, true);
    assert.equal(stale.success, false);
    console.log(JSON.stringify({ legacyPromptAccepted: legacy.success, stalePendingPromptAccepted: stale.success }));
} finally {
    try {
        await client.stop();
    } finally {
        await rm(home, { recursive: true, force: true });
    }
}
