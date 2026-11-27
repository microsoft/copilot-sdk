/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Xunit;

namespace Microsoft.Copilot.Test.Unit;

/// <summary>
/// Guards for the public shape of SDK DTOs and result factories.
/// </summary>
public class PublicDtoTests
{
    [Fact]
    public void FeedbackUpload_IsInternal_WithoutRemovingExistingPublicServerGroups()
    {
        Assert.Null(typeof(Rpc.ServerRpc).GetProperty("Feedback"));
        Assert.DoesNotContain(typeof(CopilotClient).Assembly.GetExportedTypes(),
            type => type.FullName is "Microsoft.Copilot.Rpc.ServerFeedbackApi" or "Microsoft.Copilot.Rpc.ServerFeedbackUploadApi"
                || type.Name.StartsWith("FeedbackUpload", StringComparison.Ordinal));
        Assert.Equal(typeof(Rpc.ServerPersistenceApi), typeof(Rpc.ServerRpc).GetProperty("Persistence")?.PropertyType);
        Assert.Equal(typeof(Rpc.ServerSettingsApi), typeof(Rpc.ServerRpc).GetProperty("Settings")?.PropertyType);
        Assert.Equal(typeof(Rpc.ServerModelsApi), typeof(Rpc.ServerRpc).GetProperty("Models")?.PropertyType);
    }

    [Theory]
    [InlineData(typeof(Rpc.ProtocolExternalToolDefinition))]
    [InlineData(typeof(ExternalToolRequestedData))]
    public void CallbackOwnership_IsNotPublic(Type type)
    {
        Assert.Null(type.GetProperty("HasHandler"));
    }

    [Fact]
    public void McpAuth_Result_Factories_Represent_Token_And_Cancellation()
    {
        var token = new McpAuthToken
        {
            AccessToken = "host-token",
            TokenType = "Bearer",
            ExpiresIn = 3600,
        };

        var tokenResult = McpAuthResult.FromToken(token);
        Assert.Same(token, tokenResult.Token);
        Assert.False(tokenResult.Cancelled);

        var cancelled = McpAuthResult.Cancel();
        Assert.True(cancelled.Cancelled);
        Assert.Null(cancelled.Token);
        Assert.False(cancelled.RuntimeRefresh);

        var runtimeRefresh = McpAuthResult.RefreshInRuntime();
        Assert.True(runtimeRefresh.RuntimeRefresh);
        Assert.False(runtimeRefresh.Cancelled);
        Assert.Null(runtimeRefresh.Token);
        Assert.False(new McpAuthContext().RuntimeRefreshSupported);
    }
}
