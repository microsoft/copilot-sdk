/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Rpc;
using System.Text.Json;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public class ElicitationE2ETests(E2ETestFixture fixture, ITestOutputHelper output)
    : E2ETestBase(fixture, "elicitation", output)
{

    [Fact]
    public async Task Elicitation_Throws_When_Capability_Is_Missing()
    {
        var session = await CreateSessionAsync(new CreateSessionOptions
        { Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll } });

        // Capabilities.Ui?.Elicitation should not be true by default (headless mode)
        Assert.True(session.Capabilities.Ui?.Elicitation != true);

        // Calling any UI method should throw
        var ex = await Assert.ThrowsAsync<InvalidOperationException>(async () =>
        {
            await session.Ui.ConfirmAsync("test");
        });
        Assert.Contains("not supported", ex.Message, StringComparison.OrdinalIgnoreCase);

        ex = await Assert.ThrowsAsync<InvalidOperationException>(async () =>
        {
            await session.Ui.SelectAsync("test", ["a", "b"]);
        });
        Assert.Contains("not supported", ex.Message, StringComparison.OrdinalIgnoreCase);

        ex = await Assert.ThrowsAsync<InvalidOperationException>(async () =>
        {
            await session.Ui.InputAsync("test");
        });
        Assert.Contains("not supported", ex.Message, StringComparison.OrdinalIgnoreCase);

        ex = await Assert.ThrowsAsync<InvalidOperationException>(async () =>
        {
            await session.Ui.ElicitAsync(new ElicitationParams
            {
                Message = "Enter name",
                RequestedSchema = new ElicitationSchema
                {
                    Properties = new Dictionary<string, object>() { ["name"] = new Dictionary<string, object> { ["type"] = "string" } },
                    Required = ["name"],
                },
            });
        });
        Assert.Contains("not supported", ex.Message, StringComparison.OrdinalIgnoreCase);

        await session.DisposeAsync();
    }

    [Fact]
    public async Task Convenience_Methods_Map_Handler_Results()
    {
        var confirmResponses = new Queue<ElicitationResult>([
            new ElicitationResult { Action = UIElicitationResponseAction.Accept, Content = new Dictionary<string, object> { ["confirmed"] = true }, },
            new ElicitationResult { Action = UIElicitationResponseAction.Decline, },
        ]);

        var session = await CreateSessionAsync(new CreateSessionOptions
        {
            Interaction = new SessionInteractionOptions
            {
                OnElicitationRequest = context =>
                {
                    switch (context.Message)
                    {
                        case "Confirm?":
                            Assert.Contains("confirmed", context.RequestedSchema!.Properties.Keys);
                            return Task.FromResult(confirmResponses.Dequeue());
                        case "Choose":
                            Assert.Contains("selection", context.RequestedSchema!.Properties.Keys);
                            return Task.FromResult(new ElicitationResult { Action = UIElicitationResponseAction.Accept, Content = new Dictionary<string, object> { ["selection"] = "beta" }, });
                        case "Enter value":
                            Assert.Contains("value", context.RequestedSchema!.Properties.Keys);
                            return Task.FromResult(new ElicitationResult { Action = UIElicitationResponseAction.Accept, Content = new Dictionary<string, object> { ["value"] = "typed value" }, });
                        default:
                            throw new InvalidOperationException($"Unexpected elicitation message: {context.Message}");
                    }
                }
            }
        });

        Assert.True(session.Capabilities.Ui?.Elicitation);
        Assert.True(await session.Ui.ConfirmAsync("Confirm?"));
        Assert.False(await session.Ui.ConfirmAsync("Confirm?"));
        Assert.Equal("beta", await session.Ui.SelectAsync("Choose", ["alpha", "beta"]));

        var result = await session.Ui.InputAsync("Enter value", new UiInputOptions
        {
            Title = "Value",
            Description = "A value to test",
            MinLength = 1,
            MaxLength = 20,
            Default = "default",
        });

        Assert.Equal("typed value", result);
    }

    [Fact]
    public async Task ElicitationAsync_Returns_All_Action_Shapes()
    {
        var responses = new Queue<ElicitationResult>([
            new ElicitationResult
            {
                Action = UIElicitationResponseAction.Accept,
                Content = new Dictionary<string, object> { ["name"] = "Mona" },
            },
            new ElicitationResult { Action = UIElicitationResponseAction.Decline },
            new ElicitationResult { Action = UIElicitationResponseAction.Cancel },
        ]);

        var session = await CreateSessionAsync(new CreateSessionOptions
        {
            Interaction = new SessionInteractionOptions
            {
                OnElicitationRequest = context =>
        {
            Assert.Equal("Name?", context.Message);
            return Task.FromResult(responses.Dequeue());
        }
            }
        });

        var parameters = new ElicitationParams
        {
            Message = "Name?",
            RequestedSchema = new ElicitationSchema
            {
                Properties = new Dictionary<string, object>
                {
                    ["name"] = new Dictionary<string, object> { ["type"] = "string" },
                },
                Required = ["name"],
            },
        };

        var accept = await session.Ui.ElicitAsync(parameters);
        var decline = await session.Ui.ElicitAsync(parameters);
        var cancel = await session.Ui.ElicitAsync(parameters);

        Assert.Equal(UIElicitationResponseAction.Accept, accept.Action);
        Assert.Equal("Mona", accept.Content!["name"].ToString());
        Assert.Equal(UIElicitationResponseAction.Decline, decline.Action);
        Assert.Equal(UIElicitationResponseAction.Cancel, cancel.Action);
    }

    [Fact]
    public async Task ElicitationAsync_Returns_Cancel_When_Handler_Is_Canceled()
    {
        // The handler's own cancellation, such as an HttpClient timeout, is a handler failure:
        // the SDK must still answer, or the pending elicitation never completes.
        var session = await CreateSessionAsync(new CreateSessionOptions
        { Interaction = new SessionInteractionOptions { OnElicitationRequest = _ => Task.FromCanceled<ElicitationResult>(new CancellationToken(canceled: true)) } });

        var result = await session.Ui.ElicitAsync(new ElicitationParams
        {
            Message = "Name?",
            RequestedSchema = new ElicitationSchema
            {
                Properties = new Dictionary<string, object>
                {
                    ["name"] = new Dictionary<string, object> { ["type"] = "string" },
                },
            },
        }).WaitAsync(TimeSpan.FromSeconds(30));

        Assert.Equal(UIElicitationResponseAction.Cancel, result.Action);
    }

    [Fact]
    public async Task ElicitationAsync_Returns_Collection_And_Numeric_Content_From_Handler()
    {
        var session = await CreateSessionAsync(new CreateSessionOptions
        { Interaction = new SessionInteractionOptions { OnElicitationRequest = _ => Task.FromResult(new ElicitationResult { Action = UIElicitationResponseAction.Accept, Content = new Dictionary<string, object> { ["tags"] = new List<string> { "a", "b" }, ["ratio"] = 2.5f, ["price"] = 9.99m, }, }) } });

        var result = await session.Ui.ElicitAsync(new ElicitationParams
        {
            Message = "Details?",
            RequestedSchema = new ElicitationSchema
            {
                Properties = new Dictionary<string, object>
                {
                    ["tags"] = new Dictionary<string, object>
                    {
                        ["type"] = "array",
                        ["items"] = new Dictionary<string, object> { ["type"] = "string" },
                    },
                    ["ratio"] = new Dictionary<string, object> { ["type"] = "number" },
                    ["price"] = new Dictionary<string, object> { ["type"] = "number" },
                },
            },
        }).WaitAsync(TimeSpan.FromSeconds(30));

        Assert.Equal(UIElicitationResponseAction.Accept, result.Action);
        var tags = Assert.IsType<JsonElement>(result.Content!["tags"]);
        Assert.Collection(
            tags.EnumerateArray(),
            tag => Assert.Equal("a", tag.GetString()),
            tag => Assert.Equal("b", tag.GetString()));
        Assert.Equal(2.5, Assert.IsType<JsonElement>(result.Content["ratio"]).GetDouble());
        Assert.Equal(9.99m, Assert.IsType<JsonElement>(result.Content["price"]).GetDecimal());
    }
}
