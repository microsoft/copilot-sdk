/* Copyright (c) Microsoft Corporation. All rights reserved. */

// Run seed twice with one home, then run find in a separate process to detect ambiguous names.
// Use config-dir with find to search another profile without changing the client's home.
// node session-find-by-name.mjs <runtime> <home> <seed|find> <name> [config-dir]
import { resolve } from "node:path";
import { approveAll, CopilotClient, RuntimeConnection } from "@microsoft/copilot-sdk";

const [runtime, home, action, name, configDir] = process.argv.slice(2);
if (!runtime || !home || !["seed", "find"].includes(action) || !name) {
    throw new Error(
        "Usage: node session-find-by-name.mjs <runtime> <home> <seed|find> <name> [config-dir]"
    );
}
const client = new CopilotClient({
    mode: "coding-agent",
    runtime: RuntimeConnection.forSpawnedStdio({
        path: resolve(runtime),
        env: { ...process.env, COPILOT_HOME: resolve(home) },
        workingDirectory: process.cwd(),
    }),
    github: { useLoggedInUser: false },
});
let failure;
try {
    if (action === "seed") {
        const session = await client.createSession({
            workingDirectory: process.cwd(),
            model: "example/example-model",
            inference: {
                providers: [
                    {
                        name: "example",
                        type: "openai",
                        baseUrl: "http://127.0.0.1:1",
                        apiKey: "example",
                    },
                ],
                models: [{ provider: "example", id: "example-model" }],
            },
            github: { capi: { enabled: false } },
            permissions: { onRequest: approveAll },
        });
        try {
            await session.rpc.name.set({ name });
            await client.rpc.sessions.save({ sessionId: session.sessionId });
            console.log(JSON.stringify({ outcome: "saved", sessionId: session.sessionId, name }));
        } catch (error) {
            failure = error;
        } finally {
            await session.disconnect();
        }
    } else {
        await client.start();
        const { matches } = await client.rpc.sessions.findByName({
            name,
            ...(configDir === undefined ? {} : { configDir }),
        });
        const outcome =
            matches.length > 1 ? "ambiguous" : matches.length === 1 ? "candidate" : "not_found";
        console.log(JSON.stringify({ outcome, matches }));
    }
} catch (error) {
    failure = failure ? new AggregateError([failure, error]) : error;
}
const errors = await client.stop();
if (failure || errors.length) throw new AggregateError(failure ? [failure, ...errors] : errors);
