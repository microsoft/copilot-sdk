/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

#if NET
using Microsoft.Copilot.Rpc;
using Xunit;

namespace Microsoft.Copilot.Test.Unit;

public sealed partial class ClientSessionLifetimeTests
{
    [Fact]
    public async Task Session_Import_Preserves_Positional_Dry_Run_And_Accepts_Client_Identity()
    {
        await using var server = await FakeCopilotServer.StartAsync();
        server.ResponseFactory = request => request.Method == "sessions.import"
            ? new Dictionary<string, object?>
            {
                ["ok"] = true,
                ["code"] = "dry_run",
                ["message"] = "Validated",
                ["dryRun"] = true,
                ["imported"] = new Dictionary<string, object?>
                {
                    ["sessions"] = 0,
                    ["messages"] = 0,
                    ["toolCalls"] = 0,
                    ["toolResults"] = 0,
                    ["attachments"] = 0,
                },
                ["diagnostics"] = Array.Empty<object>(),
            }
            : throw new InvalidOperationException($"Unexpected session import request '{request.Method}'.");
        await using var client = new CopilotClient(new CopilotClientOptions
        {
            Mode = CopilotClientMode.CodingAgent,
            Runtime = RuntimeConnection.ForTcp(server.Host, server.Port)
        });
        await client.StartAsync();
        server.ClearRequests();

        Func<string, string, string, string?, string?, bool?, CancellationToken, Task<SessionsImportResult>> import =
            client.Rpc.Sessions.ImportAsync;
        await import("{}", "/source.jsonl", "/workspace", null, "imported", true, CancellationToken.None);
        var legacy = Assert.Single(server.Requests);
        Assert.Equal("sessions.import", legacy.Method);
        Assert.True(legacy.Params.GetProperty("dryRun").GetBoolean());
        Assert.Equal("imported", legacy.Params.GetProperty("name").GetString());
        Assert.False(legacy.Params.TryGetProperty("clientName", out _));
        Assert.False(legacy.Params.TryGetProperty("clientKind", out _));
        server.ClearRequests();

        await client.Rpc.Sessions.ImportAsync(new SessionsImportRequest
        {
            Jsonl = "{}",
            InputPath = "/source.jsonl",
            DefaultWorkingDirectory = "/workspace",
            ClientName = "import-client",
            ClientKind = "sdk",
            DryRun = false,
        });
        var current = Assert.Single(server.Requests);
        Assert.Equal("sessions.import", current.Method);
        Assert.False(current.Params.GetProperty("dryRun").GetBoolean());
        Assert.Equal("import-client", current.Params.GetProperty("clientName").GetString());
        Assert.Equal("sdk", current.Params.GetProperty("clientKind").GetString());
    }
}
#endif
