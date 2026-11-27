/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Extensions.AI;
using System.ComponentModel;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

/// <summary>
/// Verifies that <see cref="CopilotSession.AbortAsync"/> cleanly interrupts an active
/// turn — during tool execution — without leaving dangling
/// state or causing exceptions in the event delivery pipeline.
/// </summary>
public class AbortE2ETests(E2ETestFixture fixture, ITestOutputHelper output)
    : E2ETestBase(fixture, "abort", output)
{
    [Fact]
    public async Task Should_Abort_During_Active_Tool_Execution()
    {
        var toolStarted = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseTool = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

        var session = await CreateSessionAsync(new CreateSessionOptions
        { Tools = new SessionToolOptions { Definitions = [AIFunctionFactory.Create(SlowTool, "slow_analysis")] }, Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll } });

        var cancellationNotice = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var cancellationSubscription = session.On<SessionInfoEvent>(evt =>
        {
            if (evt.Data.InfoType == "cancellation")
            {
                cancellationNotice.TrySetResult(evt.Data.Message);
            }
        });

        // Fire-and-forget
        _ = session.SendAsync(new MessageOptions
        {
            Prompt = "Use slow_analysis with value 'test_abort'. Wait for the result.",
        });

        // Wait for the tool to start executing
        var toolValue = await toolStarted.Task.WaitAsync(TimeSpan.FromSeconds(60));
        Assert.Equal("test_abort", toolValue);

        try
        {
            // Abort while the tool is running and observe cancellation before the tool is released.
            await session.AbortAsync();
            Assert.Equal("Operation cancelled by user", await cancellationNotice.Task.WaitAsync(TimeSpan.FromSeconds(10)));
        }
        finally
        {
            // Release the tool so its task doesn't leak
            releaseTool.TrySetResult("RELEASED_AFTER_ABORT");
        }

        // Session should be usable after abort — verify by listening for the right event
        var recoveryReceived = new TaskCompletionSource<AssistantMessageEvent>(TaskCreationOptions.RunContinuationsAsynchronously);
        session.On<SessionEvent>(evt =>
        {
            if (evt is AssistantMessageEvent msg && (msg.Data.Content?.Contains("tool_abort_recovery_ok") == true))
            {
                recoveryReceived.TrySetResult(msg);
            }
        });

        await session.SendAsync(new MessageOptions
        {
            Prompt = "Say 'tool_abort_recovery_ok'.",
        });

        var recoveryMessage = await recoveryReceived.Task.WaitAsync(TimeSpan.FromSeconds(60));
        Assert.Contains("tool_abort_recovery_ok", recoveryMessage.Data.Content?.ToLowerInvariant() ?? string.Empty);

        await session.DisposeAsync();

        [Description("A slow analysis tool that blocks until released")]
        async Task<string> SlowTool([Description("Value to analyze")] string value)
        {
            toolStarted.TrySetResult(value);
            return await releaseTool.Task;
        }
    }
}
