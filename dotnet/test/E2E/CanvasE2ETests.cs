/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Rpc;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public class CanvasE2ETests(E2ETestFixture fixture, ITestOutputHelper output)
    : E2ETestBase(fixture, "canvas", output)
{
    [Fact]
    public async Task Should_List_Open_Invoke_And_Close_Canvas_Through_The_Handler()
    {
        var handler = new TestCanvasHandler();
        await using var session = await CreateCanvasSessionAsync(handler);

        var canvas = Assert.Single((await session.Rpc.Canvas.ListAsync()).Canvases);
        Assert.Equal("counter", canvas.CanvasId);
        Assert.Equal("Counter", canvas.DisplayName);
        Assert.Equal("Tracks a counter value.", canvas.Description);
        Assert.Single(canvas.Actions!);
        Assert.Equal("increment", canvas.Actions![0].Name);
        Assert.Empty(handler.OpenRequests);

        var openResult = await session.Rpc.Canvas.OpenAsync(
            canvasId: "counter",
            instanceId: "counter-1",
            extensionId: canvas.ExtensionId,
            input: new Dictionary<string, object> { ["start"] = 41 });

        Assert.Equal("counter", openResult.CanvasId);
        Assert.Equal("counter-1", openResult.InstanceId);
        Assert.Equal(canvas.ExtensionId, openResult.ExtensionId);
        Assert.Equal("Counter counter-1", openResult.Title);
        Assert.Equal("ready", openResult.Status);
        Assert.Equal("https://example.com/counter/counter-1", openResult.Url);

        var openRequest = Assert.Single(handler.OpenRequests);
        Assert.Equal(session.SessionId, openRequest.SessionId);
        Assert.Equal(canvas.ExtensionId, openRequest.ExtensionId);
        Assert.Equal("counter", openRequest.CanvasId);
        Assert.Equal("counter-1", openRequest.InstanceId);
        Assert.Equal(41, GetRequiredInt32(openRequest.Input, "start"));

        var openCanvases = await session.Rpc.Canvas.ListOpenAsync();
        Assert.Single(openCanvases.OpenCanvases);
        Assert.Equal("counter-1", openCanvases.OpenCanvases[0].InstanceId);

        var actionResult = await session.Rpc.Canvas.Action.InvokeAsync(
            instanceId: "counter-1",
            actionName: "increment",
            input: new Dictionary<string, object> { ["delta"] = 1 });

        var actionRequest = Assert.Single(handler.ActionRequests);
        Assert.Equal(session.SessionId, actionRequest.SessionId);
        Assert.Equal(canvas.ExtensionId, actionRequest.ExtensionId);
        Assert.Equal("counter", actionRequest.CanvasId);
        Assert.Equal("counter-1", actionRequest.InstanceId);
        Assert.Equal("increment", actionRequest.ActionName);
        Assert.Equal(1, GetRequiredInt32(actionRequest.Input, "delta"));
        Assert.True(actionResult.Result.HasValue);
        Assert.NotEqual(JsonValueKind.Undefined, actionResult.Result.Value.ValueKind);

        await session.Rpc.Canvas.CloseAsync("counter-1");

        var closeRequest = Assert.Single(handler.CloseRequests);
        Assert.Equal(session.SessionId, closeRequest.SessionId);
        Assert.Equal(canvas.ExtensionId, closeRequest.ExtensionId);
        Assert.Equal("counter", closeRequest.CanvasId);
        Assert.Equal("counter-1", closeRequest.InstanceId);
        Assert.Empty((await session.Rpc.Canvas.ListOpenAsync()).OpenCanvases);
    }

    private Task<CopilotSession> CreateCanvasSessionAsync(TestCanvasHandler handler)
    {
        return CreateSessionAsync(new CreateSessionOptions
        { Permissions = new SessionPermissionOptions { OnRequest = PermissionHandler.ApproveAll }, Canvases = new SessionCanvasOptions { RequestRenderer = true, Definitions = [new CanvasDeclaration { Id = "counter", DisplayName = "Counter", Description = "Tracks a counter value.", Actions = [new CanvasAction { Name = "increment", Description = "Increments the counter.", }], }], Handler = handler }, Extensions = new SessionExtensionOptions { Identity = new ExtensionInfo { Source = "dotnet-sdk-tests", Name = "canvas-provider" } } });
    }

    private static int GetRequiredInt32(JsonElement? element, string propertyName)
    {
        Assert.True(element.HasValue);
        return element.Value.GetProperty(propertyName).GetInt32();
    }

    private sealed class TestCanvasHandler : CanvasHandlerBase
    {
        public List<CanvasProviderOpenRequest> OpenRequests { get; } = [];
        public List<CanvasProviderCloseRequest> CloseRequests { get; } = [];
        public List<CanvasProviderInvokeActionRequest> ActionRequests { get; } = [];

        public override Task<CanvasProviderOpenResult> OnOpenAsync(CanvasProviderOpenRequest context, CancellationToken cancellationToken)
        {
            OpenRequests.Add(Clone(context));
            return Task.FromResult(new CanvasProviderOpenResult
            {
                Url = $"https://example.com/counter/{context.InstanceId}",
                Title = $"Counter {context.InstanceId}",
                Status = "ready",
            });
        }

        public override Task OnCloseAsync(CanvasProviderCloseRequest context, CancellationToken cancellationToken)
        {
            CloseRequests.Add(Clone(context));
            return Task.CompletedTask;
        }

        public override Task<object?> OnActionAsync(CanvasProviderInvokeActionRequest context, CancellationToken cancellationToken)
        {
            ActionRequests.Add(Clone(context));
            var openRequest = OpenRequests.LastOrDefault(request => request.InstanceId == context.InstanceId);
            var current = openRequest is not null && openRequest.Input.HasValue
                ? openRequest.Input.Value.GetProperty("start").GetInt32()
                : 0;
            var delta = context.Input.HasValue
                ? context.Input.Value.GetProperty("delta").GetInt32()
                : 0;
            using var document = JsonDocument.Parse($@"{{""count"":{current + delta}}}");
            return Task.FromResult<object?>(document.RootElement.Clone());
        }

        private static CanvasProviderOpenRequest Clone(CanvasProviderOpenRequest request)
            => new()
            {
                SessionId = request.SessionId,
                ExtensionId = request.ExtensionId,
                CanvasId = request.CanvasId,
                InstanceId = request.InstanceId,
                Input = Clone(request.Input),
                Host = Clone(request.Host),
            };

        private static CanvasProviderCloseRequest Clone(CanvasProviderCloseRequest request)
            => new()
            {
                SessionId = request.SessionId,
                ExtensionId = request.ExtensionId,
                CanvasId = request.CanvasId,
                InstanceId = request.InstanceId,
                Host = Clone(request.Host),
            };

        private static CanvasProviderInvokeActionRequest Clone(CanvasProviderInvokeActionRequest request)
            => new()
            {
                SessionId = request.SessionId,
                ExtensionId = request.ExtensionId,
                CanvasId = request.CanvasId,
                InstanceId = request.InstanceId,
                ActionName = request.ActionName,
                Input = Clone(request.Input),
                Host = Clone(request.Host),
            };

        private static JsonElement? Clone(JsonElement? element)
        {
            if (!element.HasValue)
            {
                return null;
            }

            using var document = JsonDocument.Parse(element.Value.GetRawText());
            return document.RootElement.Clone();
        }

        private static CanvasHostContext? Clone(CanvasHostContext? host)
        {
            if (host is null)
            {
                return null;
            }

            return new CanvasHostContext
            {
                Capabilities = host.Capabilities is null
                    ? null
                    : new CanvasHostContextCapabilities
                    {
                        Canvases = host.Capabilities.Canvases,
                    },
            };
        }
    }
}
