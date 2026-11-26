/* Copyright (c) Microsoft Corporation. All rights reserved. */

using Microsoft.Copilot.Rpc;
using Xunit;

#pragma warning disable MCPSDK001 // Exercise the shipped experimental RPC signature.

namespace Microsoft.Copilot.Test.Unit;

public class PermissionPromptCompatibilityTests
{
    [Fact]
    public void LegacyPromptMethodRetainsItsBinarySignature()
    {
        var method = typeof(PermissionsApi).GetMethod(
            nameof(PermissionsApi.NotifyPromptShownAsync),
            [typeof(string), typeof(CancellationToken)]);

        Assert.NotNull(method);
        Assert.Equal(typeof(Task<PermissionsNotifyPromptShownResult>), method.ReturnType);
        Assert.True(method.GetParameters()[1].IsOptional);
    }

    private static Task<PermissionsNotifyPromptShownResult> LegacySourceCall(
        PermissionsApi permissions, string message, CancellationToken token) =>
        permissions.NotifyPromptShownAsync(message, token);
}
