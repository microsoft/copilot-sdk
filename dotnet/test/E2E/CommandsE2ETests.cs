/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Rpc;
using Microsoft.Copilot.Test.Harness;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public class CommandsE2ETests(E2ETestFixture fixture, ITestOutputHelper output)
    : E2ETestBase(fixture, "commands", output)
{
    private static readonly string[] KnownBuiltinCommands = ["help", "model", "compact"];

    [Fact]
    public async Task Session_Commands_List_Returns_Builtins_And_Respects_Client_Command_Filter()
    {
        var session = await CreateSessionAsync(new CreateSessionOptions
        { Interaction = new SessionInteractionOptions { Commands = [new CommandDefinition { Name = "deploy", Description = "Deploy the app", Handler = _ => Task.CompletedTask }, new CommandDefinition { Name = "rollback", Description = "Rollback the app", Handler = _ => Task.CompletedTask },] } });

        CommandList? clientCommands = null;
        await TestHelper.WaitForConditionAsync(
            async () =>
            {
                clientCommands = await session.Rpc.Commands.ListAsync(new SessionCommandsListRequest
                {
                    IncludeBuiltins = false,
                    IncludeClientCommands = true,
                    IncludeSkills = false,
                });
                return clientCommands.Commands.Any(c => IsCommand(c, "deploy", SlashCommandKind.Client)) &&
                    clientCommands.Commands.Any(c => IsCommand(c, "rollback", SlashCommandKind.Client));
            },
            timeout: TimeSpan.FromSeconds(30),
            timeoutMessage: "Timed out waiting for client commands to be listed.");
        Assert.Contains(clientCommands!.Commands, c => IsCommand(c, "deploy", SlashCommandKind.Client));
        Assert.Contains(clientCommands.Commands, c => IsCommand(c, "rollback", SlashCommandKind.Client));
        Assert.DoesNotContain(clientCommands.Commands, c => c.Kind == SlashCommandKind.Builtin);

        var builtinCommands = await session.Rpc.Commands.ListAsync(new SessionCommandsListRequest
        {
            IncludeBuiltins = true,
            IncludeClientCommands = false,
            IncludeSkills = false,
        });
        Assert.True(
            builtinCommands.Commands.Any(IsKnownBuiltin),
            $"Expected a known built-in command. Actual commands: {FormatCommands(builtinCommands.Commands)}");
        Assert.DoesNotContain(builtinCommands.Commands, c => string.Equals(c.Name, "deploy", StringComparison.OrdinalIgnoreCase));

        await session.DisposeAsync();
    }

    [Fact]
    public async Task Session_Commands_Execute_Runs_Registered_Command_Handler()
    {
        var contextReceived = new TaskCompletionSource<CommandContext>(TaskCreationOptions.RunContinuationsAsynchronously);
        var session = await CreateSessionAsync(new CreateSessionOptions
        {
            Interaction = new SessionInteractionOptions
            {
                Commands = [new CommandDefinition { Name = "deploy", Description = "Deploy the app", Handler = ctx =>
{
    contextReceived.TrySetResult(ctx);
    return Task.CompletedTask;
}, }, ]
            }
        });

        await WaitForClientCommandAsync(session, "deploy");

        var result = await session.Rpc.Commands.ExecuteAsync("deploy", "production");

        Assert.Null(result.Error);
        var capturedContext = await contextReceived.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(session.SessionId, capturedContext.SessionId);
        Assert.Equal("/deploy production", capturedContext.Command);
        Assert.Equal("deploy", capturedContext.CommandName);
        Assert.Equal("production", capturedContext.Args);

        await session.DisposeAsync();
    }

    [Fact]
    public async Task Session_Commands_Execute_Reports_Error_When_Handler_Is_Canceled()
    {
        // The handler's own cancellation, such as an HttpClient timeout, is a handler failure:
        // the SDK must still answer, or the pending command never completes.
        var session = await CreateSessionAsync(new CreateSessionOptions
        { Interaction = new SessionInteractionOptions { Commands = [new CommandDefinition { Name = "deploy", Description = "Deploy the app", Handler = _ => Task.FromCanceled(new CancellationToken(canceled: true)), },] } });

        await WaitForClientCommandAsync(session, "deploy");

        var result = await session.Rpc.Commands.ExecuteAsync("deploy", "production")
            .WaitAsync(TimeSpan.FromSeconds(30));

        Assert.NotNull(result.Error);
        Assert.Contains("A task was canceled.", result.Error);

        await session.DisposeAsync();
    }

    private static Task WaitForClientCommandAsync(CopilotSession session, string name)
        => TestHelper.WaitForConditionAsync(
            async () =>
            {
                var commands = await session.Rpc.Commands.ListAsync(new SessionCommandsListRequest
                {
                    IncludeBuiltins = false,
                    IncludeClientCommands = true,
                    IncludeSkills = false,
                });
                return commands.Commands.Any(c => IsCommand(c, name, SlashCommandKind.Client));
            },
            timeout: TimeSpan.FromSeconds(30),
            timeoutMessage: "Timed out waiting for registered command to be listed.");

    private static bool IsCommand(SlashCommandInfo command, string name, SlashCommandKind kind)
    {
        return string.Equals(command.Name, name, StringComparison.OrdinalIgnoreCase) && command.Kind == kind;
    }

    private static bool IsKnownBuiltin(SlashCommandInfo command)
    {
        return command.Kind == SlashCommandKind.Builtin &&
            KnownBuiltinCommands.Contains(command.Name, StringComparer.OrdinalIgnoreCase);
    }

    private static string FormatCommands(IEnumerable<SlashCommandInfo> commands)
    {
        return string.Join(", ", commands.Select(c => $"{c.Name}:{c.Kind.Value}"));
    }
}
