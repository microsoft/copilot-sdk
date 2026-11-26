/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

import type { CopilotSession } from "../src/index.js";

/** Render runtime-owned context choices without a second pricing policy. */
export async function formatModelContextTiers(session: CopilotSession): Promise<string> {
    await session.rpc.model.list({});
    const policy = await session.rpc.model.getSelectionPolicy();
    return JSON.stringify(
        policy.models
            .filter((model) => model.contextTiers?.length)
            .map(({ modelId, providerId, contextTiers, defaultContextTier }) => ({
                modelId,
                providerId,
                contextTiers,
                defaultContextTier,
            })),
        null,
        2
    );
}
