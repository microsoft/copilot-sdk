/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

import type { createInternalSessionRpc } from "../src/generated/rpc.js";

/** Refine a composer's draft without an agent turn or conversation changes. */
export async function refineDraft(rpc: ReturnType<typeof createInternalSessionRpc>, text: string) {
    const outcome = await rpc.model.refinePrompt({ text });
    console.log("Prompt refinement:", JSON.stringify(outcome));
    return outcome;
}
