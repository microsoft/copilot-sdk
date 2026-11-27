/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Test.Harness;
using Microsoft.Extensions.AI;
using System.ComponentModel;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

/// <summary>
/// Representative scenario coverage for host-owned tools.
/// </summary>
public class ScenarioTestingToolsE2ETests(E2ETestFixture fixture, ITestOutputHelper output)
    : ScenarioTestingE2ETestBase(fixture, "scenario_testing_tools", output)
{
    private static readonly TimeSpan EventTimeout = TimeSpan.FromSeconds(60);

    [Fact]
    public async Task Should_Advertise_Scenario_Tool_Schema_Override_And_Availability()
    {
        var hiddenToolCalled = false;
        await using var session = await CreateSessionAsync(new CreateSessionOptions
        { ClientName = "scenario-client", Tools = new SessionToolOptions { Definitions = [CopilotTool.DefineTool((Func<string, int, string>)LookupIssue, factoryOptions: new AIFunctionFactoryOptions { Name = "scenario_lookup_issue", Description = "Looks up an issue in the scenario client installation.", }), CopilotTool.DefineTool((Func<string, string>)ScenarioGrep, new CopilotToolOptions { OverridesBuiltInTool = true }, new AIFunctionFactoryOptions { Name = "grep", Description = "Searches the scenario-owned index.", }), CopilotTool.DefineTool((Func<string>)HiddenAdminTool, factoryOptions: new AIFunctionFactoryOptions { Name = "scenario_hidden_admin" }),], AvailableTools = new ToolSet().AddCustom("scenario_lookup_issue").AddCustom("grep"), ExcludedTools = new ToolSet().AddCustom("scenario_hidden_admin") } });

        var response = await session.SendAndWaitAsync(new MessageOptions
        {
            Prompt = "Call scenario_lookup_issue for owner octo and issue number 42. Reply with its result.",
        });

        Assert.Contains("SCENARIO_ISSUE_octo_42", response?.Data.Content ?? string.Empty, StringComparison.Ordinal);
        Assert.False(hiddenToolCalled);

        var exchange = (await Ctx.GetExchangesAsync()).Last();
        var names = GetToolNames(exchange);
        Assert.Contains("scenario_lookup_issue", names);
        Assert.Contains("grep", names);
        Assert.DoesNotContain("scenario_hidden_admin", names);
        Assert.Equal(1, names.Count(name => name == "grep"));

        var lookup = Assert.Single(exchange.Request.Tools!, tool => tool.Function.Name == "scenario_lookup_issue");
        Assert.Equal("Looks up an issue in the scenario client installation.", lookup.Function.Description);
        var parameters = lookup.Function.Parameters!.Value;
        Assert.Equal("object", parameters.GetProperty("type").GetString());
        Assert.Equal("string", parameters.GetProperty("properties").GetProperty("owner").GetProperty("type").GetString());
        Assert.Equal("integer", parameters.GetProperty("properties").GetProperty("number").GetProperty("type").GetString());

        static string LookupIssue(
            [Description("Repository owner")] string owner,
            [Description("Issue number")] int number) =>
            $"SCENARIO_ISSUE_{owner}_{number}";

        static string ScenarioGrep([Description("Search query")] string query) => $"SCENARIO_GREP_{query}";

        string HiddenAdminTool()
        {
            hiddenToolCalled = true;
            return "SHOULD_NOT_RUN";
        }
    }

    [Fact]
    public async Task Should_Preserve_Scenario_Tool_Invocation_Identity_Arguments_And_Text()
    {
        ToolInvocation? observedInvocation = null;
        var toolCompleted = new TaskCompletionSource<ToolExecutionCompleteEvent>(
            TaskCreationOptions.RunContinuationsAsynchronously);

        await using var session = await CreateSessionAsync(new CreateSessionOptions
        { ClientName = "scenario-client", Tools = new SessionToolOptions { Definitions = [CopilotTool.DefineTool((Func<string, ToolInvocation, TextContent>)SearchPullRequests, factoryOptions: new AIFunctionFactoryOptions { Name = "scenario_search_pull_requests", Description = "Searches pull requests visible to the scenario client.", }),] } });
        using var subscription = session.On<ToolExecutionCompleteEvent>(evt =>
            toolCompleted.TrySetResult(evt));

        var response = await session.SendAndWaitAsync(new MessageOptions
        {
            Prompt = "Call scenario_search_pull_requests with query is:open label:bug. Reply with its result.",
        });
        var completed = await toolCompleted.Task.WaitAsync(EventTimeout);

        Assert.NotNull(observedInvocation);
        Assert.Equal(session.SessionId, observedInvocation!.SessionId);
        Assert.Equal("scenario_search_pull_requests", observedInvocation.ToolName);
        Assert.False(string.IsNullOrWhiteSpace(observedInvocation.ToolCallId));
        Assert.Equal("is:open label:bug", observedInvocation.Arguments!.Value.GetProperty("query").GetString());
        Assert.Equal(observedInvocation.ToolCallId, completed.Data.ToolCallId);
        Assert.True(completed.Data.Success);
        Assert.Contains("SCENARIO_SEARCH_TEXT", response?.Data.Content ?? string.Empty, StringComparison.Ordinal);

        TextContent SearchPullRequests(
            [Description("GitHub search query")] string query,
            ToolInvocation invocation)
        {
            observedInvocation = invocation;
            return new TextContent($"SCENARIO_SEARCH_TEXT:{query}");
        }
    }
}
