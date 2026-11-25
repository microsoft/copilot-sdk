/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@AllowCopilotExperimental
final class EmptySkillProvider implements SkillProvider {
    @Override
    public CompletableFuture<List<SkillProviderDescriptor>> listSkills() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<String> readSkill(String name) {
        return CompletableFuture.completedFuture(null);
    }
}
