/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Test.Harness;
using Microsoft.Extensions.AI;
using System.ComponentModel;
using System.Text.Json;
using System.Text.Json.Serialization;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public partial class ToolResultsE2ETests(E2ETestFixture fixture, ITestOutputHelper output) : E2ETestBase(fixture, "tool_results", output)
{
    [JsonSourceGenerationOptions(JsonSerializerDefaults.Web)]
    [JsonSerializable(typeof(ToolResultAIContent))]
    [JsonSerializable(typeof(ToolResultObject))]
    [JsonSerializable(typeof(JsonElement))]
    private partial class ToolResultsJsonContext : JsonSerializerContext;

    [Fact]
    public async Task Should_Handle_Tool_Result_With_Failure_ResultType()
    {
        var session = await CreateSessionAsync(new CreateSessionOptions
        { Tools = new SessionToolOptions { Definitions = [AIFunctionFactory.Create(CheckStatus, "check_status", serializerOptions: ToolResultsJsonContext.Default.Options)] }, Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll } });

        var assistantMessage = await TestHelper.SendAndGetFinalAssistantMessageAsync(session, new MessageOptions
        {
            Prompt = "Check the status of the service using check_status. If it fails, say 'service is down'."
        });

        Assert.NotNull(assistantMessage);
        Assert.Contains("service is down", assistantMessage!.Data.Content?.ToLowerInvariant() ?? string.Empty);

        [Description("Checks the status of a service")]
        static ToolResultAIContent CheckStatus()
            => new(new()
            {
                TextResultForLlm = "Service unavailable",
                ResultType = "failure",
                Error = "API timeout",
            });
    }

    [Fact]
    public async Task Should_Preserve_ToolTelemetry_And_Not_Stringify_Structured_Results_For_LLM()
    {
        var session = await CreateSessionAsync(new CreateSessionOptions
        { Tools = new SessionToolOptions { Definitions = [AIFunctionFactory.Create(AnalyzeCode, "analyze_code", serializerOptions: ToolResultsJsonContext.Default.Options)] }, Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll } });

        var assistantMessage = await TestHelper.SendAndGetFinalAssistantMessageAsync(session, new MessageOptions
        {
            Prompt = "Analyze the file main.ts for issues."
        });

        Assert.NotNull(assistantMessage);
        Assert.Contains("no issues", assistantMessage!.Data.Content?.ToLowerInvariant() ?? string.Empty);

        // Verify the LLM received just textResultForLlm, not stringified JSON
        var traffic = await Ctx.GetExchangesAsync();
        var lastConversation = traffic[^1];

        var toolResults = lastConversation.Request.Messages
            .Where(m => m.Role == "tool")
            .ToList();

        Assert.Single(toolResults);
        Assert.Equal("Analysis of main.ts: no issues found", toolResults[0].StringContent);
        Assert.DoesNotContain("toolTelemetry", toolResults[0].StringContent);
        Assert.DoesNotContain("resultType", toolResults[0].StringContent);

        [Description("Analyzes code for issues")]
        static ToolResultAIContent AnalyzeCode([Description("File to analyze")] string file)
            => new(new()
            {
                TextResultForLlm = $"Analysis of {file}: no issues found",
                ResultType = "success",
                ToolTelemetry = new Dictionary<string, object>
                {
                    ["metrics"] = JsonDocument.Parse("""{"analysisTimeMs":150}""").RootElement.Clone(),
                    ["properties"] = JsonDocument.Parse("""{"analyzer":"eslint"}""").RootElement.Clone(),
                },
            });
    }

    [Fact]
    public async Task Should_Handle_Tool_Result_With_Rejected_ResultType()
    {
        var toolExecutionComplete = new TaskCompletionSource<ToolExecutionCompleteEvent>(TaskCreationOptions.RunContinuationsAsynchronously);
        var toolHandlerCalled = false;

        var session = await CreateSessionAsync(new CreateSessionOptions
        { Tools = new SessionToolOptions { Definitions = [AIFunctionFactory.Create(AttemptDeploy, "deploy_service", serializerOptions: ToolResultsJsonContext.Default.Options)] }, Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll } });

        session.On<SessionEvent>(evt =>
        {
            if (evt is ToolExecutionCompleteEvent toolEvt)
            {
                toolExecutionComplete.TrySetResult(toolEvt);
            }
        });
        var idle = TestHelper.GetNextEventOfTypeAsync<SessionIdleEvent>(session);

        await session.SendAsync(new MessageOptions
        {
            Prompt = "Deploy the service using deploy_service. If it's rejected, tell me it was 'rejected by policy'."
        });

        var toolEvt = await toolExecutionComplete.Task.WaitAsync(TimeSpan.FromSeconds(60));
        // The tool handler was called and returned a "rejected" result
        Assert.True(toolHandlerCalled, "Tool handler should have been called");
        Assert.NotNull(toolEvt);
        Assert.False(toolEvt.Data.Success);
        Assert.Equal("rejected", toolEvt.Data.Error?.Code);
        Assert.Contains("Deployment rejected", toolEvt.Data.Error?.Message ?? string.Empty);

        // A rejected tool result may complete the turn without a follow-up assistant
        // message; the stable contract is the tool result event plus session idle.
        await idle;

        [Description("Deploys a service")]
        ToolResultAIContent AttemptDeploy()
        {
            toolHandlerCalled = true;
            return new(new()
            {
                TextResultForLlm = "Deployment rejected: policy violation - production deployments require approval",
                ResultType = "rejected",
            });
        }
    }
}
