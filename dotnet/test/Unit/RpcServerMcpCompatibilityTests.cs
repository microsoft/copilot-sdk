/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

#if NET
using System.Text.Json;
using Microsoft.Copilot.Rpc;
using Xunit;

namespace Microsoft.Copilot.Test.Unit;

public sealed partial class ClientSessionLifetimeTests
{
    [Fact]
    public async Task Config_Mutations_Preserve_Cancellation_Arguments_And_Accept_Request_Roots()
    {
        await using var server = await FakeCopilotServer.StartAsync();
        server.ResponseFactory = ServerMcpCompatibilityResponse;
        await using var client = new CopilotClient(new CopilotClientOptions
        {
            Mode = CopilotClientMode.CodingAgent,
            Runtime = RuntimeConnection.ForTcp(server.Host, server.Port)
        });
        await client.StartAsync();
        server.ClearRequests();
        using var config = JsonDocument.Parse("""{ "command": "node" }""");
        const string metadataUrl = "https://example.test/oauth-client.json";
        const string selectedRoot = "/profiles/selected";
        var cancellation = CancellationToken.None;

        await client.Rpc.Mcp.Config.AddAsync("manual", config.RootElement, cancellation);
        await client.Rpc.Mcp.Config.RemoveAsync("manual", metadataUrl, cancellation);
        await client.Rpc.Mcp.Config.EnableAsync(["manual"], cancellation);
        await client.Rpc.Mcp.Config.DisableAsync(["manual"], cancellation);

        var legacy = server.Requests.ToArray();
        Assert.Equal(4, legacy.Length);
        Assert.All(legacy, request => Assert.False(request.Params.TryGetProperty("configDir", out _)));
        Assert.Equal(metadataUrl, legacy[1].Params.GetProperty("authClientIdMetadataUrl").GetString());
        server.ClearRequests();

        await client.Rpc.Mcp.Config.AddAsync(new McpConfigAddRequest
        {
            Name = "manual",
            Config = config.RootElement,
            ConfigDir = selectedRoot,
        }, cancellation);
        await client.Rpc.Mcp.Config.RemoveAsync(new McpConfigRemoveRequest
        {
            Name = "manual",
            AuthClientIdMetadataUrl = metadataUrl,
            ConfigDir = selectedRoot,
        }, cancellation);
        await client.Rpc.Mcp.Config.EnableAsync(new McpConfigEnableRequest
        {
            Names = ["manual"],
            ConfigDir = selectedRoot,
        }, cancellation);
        await client.Rpc.Mcp.Config.DisableAsync(new McpConfigDisableRequest
        {
            Names = ["manual"],
            ConfigDir = selectedRoot,
        }, cancellation);

        var rooted = server.Requests.ToArray();
        Assert.Equal(4, rooted.Length);
        Assert.All(rooted, request => Assert.Equal(selectedRoot, request.Params.GetProperty("configDir").GetString()));
        for (var index = 0; index < legacy.Length; index++)
        {
            Assert.Equal(legacy[index].Method, rooted[index].Method);
            foreach (var property in legacy[index].Params.EnumerateObject())
                Assert.Equal(property.Value.GetRawText(), rooted[index].Params.GetProperty(property.Name).GetRawText());
        }
    }

    [Fact]
    public async Task Request_Overload_Rejects_Missing_Required_Inputs()
    {
        await using var server = await FakeCopilotServer.StartAsync();
        server.ResponseFactory = ServerMcpCompatibilityResponse;
        await using var client = new CopilotClient(new CopilotClientOptions
        {
            Mode = CopilotClientMode.CodingAgent,
            Runtime = RuntimeConnection.ForTcp(server.Host, server.Port)
        });
        await client.StartAsync();
        server.ClearRequests();
        await Assert.ThrowsAsync<ArgumentNullException>(() => client.Rpc.Mcp.PlanInstallAsync(contract: null!, source: new McpPlanInstallSourceCandidate { CandidateHandle = "candidate", SearchId = "search" }));
        await Assert.ThrowsAsync<ArgumentNullException>(() => client.Rpc.Mcp.PlanInstallAsync(
            new CatalogClientContract { ProtocolVersion = 3, RequiredCapabilities = [] }, null!));
        Assert.Empty(server.Requests);
    }

    private static Dictionary<string, object?> ServerMcpCompatibilityResponse(RpcRequestRecord request) =>
        request.Method switch
        {
            "mcp.config.add" or "mcp.config.remove" or "mcp.config.enable" or "mcp.config.disable" =>
                new Dictionary<string, object?>(),
            _ => throw new InvalidOperationException($"Unexpected server MCP compatibility request '{request.Method}'."),
        };
}
#endif
