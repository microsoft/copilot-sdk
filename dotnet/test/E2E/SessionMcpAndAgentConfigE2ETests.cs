/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Rpc;
using Microsoft.Copilot.Test.Harness;
using Microsoft.Extensions.AI;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public class SessionMcpAndAgentConfigE2ETests(E2ETestFixture fixture, ITestOutputHelper output) : E2ETestBase(fixture, "mcp_and_agents", output)
{
    [Fact]
    public async Task Should_Accept_MCP_Server_Configuration_On_Session_Resume()
    {
        // Create a session first
        var session1 = await CreateSessionAsync();
        var sessionId = session1.SessionId;
        await session1.SendAndWaitAsync(new MessageOptions { Prompt = "What is 1+1?" });
        await session1.DisposeAsync();

        // Resume with MCP servers
        var session2 = await ResumeSessionAsync(sessionId, new ResumeSessionOptions
        { Mcp = new SessionMcpOptions { Servers = CreateTestMcpServers("test-server") } });

        Assert.Equal(sessionId, session2.SessionId);
        await WaitForMcpServerStatusAsync(session2, "test-server", McpServerStatus.Connected);

        await session2.DisposeAsync();
    }

    [Fact]
    public async Task Should_Pass_Literal_Env_Values_To_Mcp_Server_Subprocess()
    {
        var testHarnessDir = FindTestHarnessDir();
        var mcpServers = new Dictionary<string, McpServerConfig>
        {
            ["env-echo"] = new McpStdioServerConfig
            {
                Command = "node",
                Args = [Path.Combine(testHarnessDir, "test-mcp-server.mjs")],
                Env = new Dictionary<string, string> { ["TEST_SECRET"] = "hunter2" },
                WorkingDirectory = testHarnessDir,
                Tools = ["*"]
            }
        };

        var session = await CreateSessionAsync(new CreateSessionOptions
        { Mcp = new SessionMcpOptions { Servers = mcpServers }, Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll } });

        Assert.Matches(@"^[a-f0-9-]+$", session.SessionId);

        var message = await session.SendAndWaitAsync(new MessageOptions
        {
            Prompt = "Use the env-echo/get_env tool to read the TEST_SECRET environment variable. Reply with just the value, nothing else."
        });

        Assert.NotNull(message);
        Assert.Contains("hunter2", message!.Data.Content);

        await session.DisposeAsync();
    }

    [Fact]
    public async Task Should_Round_Trip_Mcp_Server_Elicitation_Request()
    {
        var testHarnessDir = FindTestHarnessDir();
        var configPath = Path.Join(Ctx.WorkDir, $"elicitation-config-{Guid.NewGuid():N}.json");
        await File.WriteAllTextAsync(
            configPath,
            """
            [
              {
                "message": "Pick a color.",
                "requestedSchema": {
                  "type": "object",
                  "properties": {
                    "color": {
                      "type": "string",
                      "enum": ["red", "blue"]
                    }
                  },
                  "required": ["color"]
                }
              }
            ]
            """);

        var elicitationContext = new TaskCompletionSource<ElicitationContext>(TaskCreationOptions.RunContinuationsAsynchronously);
        var mcpServers = new Dictionary<string, McpServerConfig>
        {
            ["test-elicitation-server"] = new McpStdioServerConfig
            {
                Command = "node",
                Args =
                [
                    Path.Join(testHarnessDir, "test-mcp-elicitation-server.mjs"),
                    "--config",
                    configPath
                ],
                WorkingDirectory = testHarnessDir,
                Tools = ["*"]
            }
        };

        var session = await CreateSessionAsync(new CreateSessionOptions
        {
            Mcp = new SessionMcpOptions { Servers = mcpServers },
            Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll },
            Interaction = new SessionInteractionOptions
            {
                OnElicitationRequest = context =>
        {
            elicitationContext.TrySetResult(context);
            return Task.FromResult(new ElicitationResult { Action = UIElicitationResponseAction.Accept, Content = new Dictionary<string, object> { ["color"] = "blue" } });
        }
            }
        });

        await WaitForMcpServerStatusAsync(session, "test-elicitation-server", McpServerStatus.Connected);

        var message = await session.SendAndWaitAsync(new MessageOptions
        {
            Prompt = "Use the test-elicitation-server-request_user_input tool and tell me the chosen color. Reply with just the color."
        });

        var request = await elicitationContext.Task.WaitAsync(TimeSpan.FromSeconds(60));

        Assert.Equal("Pick a color.", request.Message);
        Assert.Equal(ElicitationRequestedMode.Form, request.Mode);
        Assert.Contains("test-elicitation-server", request.ElicitationSource ?? string.Empty, StringComparison.Ordinal);
        Assert.NotNull(request.RequestedSchema);
        Assert.Equal("object", request.RequestedSchema!.Type);
        Assert.Contains("color", request.RequestedSchema.Properties.Keys);
        Assert.Contains("blue", message?.Data.Content ?? string.Empty);

        await session.DisposeAsync();
    }

    [Fact]
    public async Task Should_Keep_Never_Deferred_Mcp_Server_Tools_Out_Of_Tool_Search()
    {
        const string deferredServer = "sdk-auto-defer-server";
        const string pinnedServer = "sdk-never-defer-server";
        var env = Ctx.GetEnvironment();
        env["TOOL_SEARCH"] = "true";
        env["TOOL_SEARCH_ANTHROPIC"] = "true";
        env["COPILOT_EXP_COPILOT_CLI_TOOL_SEARCH_ANTHROPIC"] = "true";
        env["TOOL_SEARCH_BUILTIN_ANTHROPIC"] = "false";
        env["COPILOT_EXP_COPILOT_CLI_TOOL_SEARCH_BUILTIN_ANTHROPIC"] = "false";
        env["TOOL_SEARCH_CLIENT_GENERIC"] = "false";
        env["COPILOT_EXP_COPILOT_CLI_TOOL_SEARCH_CLIENT_GENERIC"] = "false";

        await using var client = Ctx.CreateClient(
            options: new CopilotClientOptions { Mode = CopilotClientMode.CodingAgent, Runtime = RuntimeConnection.ForSpawnedStdio() },
            environment: env);

        var mcpServers = CreateTestMcpServers(deferredServer, pinnedServer);
        mcpServers[pinnedServer].DeferTools = CopilotToolDefer.Never;

        await using var session = await Ctx.CreateSessionAsync(client, new CreateSessionOptions
        {
            Model = "claude-sonnet-5",
            Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll },
            Tools = new SessionToolOptions
            {
                Search = new ToolSearchConfig { Enabled = true, DeferThreshold = 30 },
                Definitions = [.. Enumerable.Range(0, 32).Select(index =>
                    AIFunctionFactory.Create(SearchFiller, $"sdk_mcp_search_filler_{index}", $"Search filler {index}"))],
            },
            Mcp = new SessionMcpOptions { Servers = mcpServers },
        });

        await WaitForMcpServerStatusAsync(session, deferredServer, McpServerStatus.Connected);
        await WaitForMcpServerStatusAsync(session, pinnedServer, McpServerStatus.Connected);

        var message = await session.SendAndWaitAsync(new MessageOptions { Prompt = "Reply with exactly SDK_NEVER_DEFER_MCP_DONE and nothing else. Do not call any tools." });
        Assert.Equal("SDK_NEVER_DEFER_MCP_DONE", message?.Data.Content);

        var tools = (await session.Rpc.Tools.GetCurrentMetadataAsync()).Tools;
        Assert.NotNull(tools);
        bool IsDeferred(string server) => Assert.Single(
            tools,
            tool => tool.McpServerName == server && tool.McpToolName == "get_env").DeferLoading == true;
        Assert.True(IsDeferred(deferredServer));
        Assert.False(IsDeferred(pinnedServer));
    }

    private static string SearchFiller() => "FILLER";
}
