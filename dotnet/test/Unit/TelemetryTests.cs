/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using System.Diagnostics;
using System.Reflection;
using Xunit;

namespace Microsoft.Copilot.Test.Unit;

public class TelemetryTests
{
    [Fact]
    public void TelemetryHelpers_Restores_W3C_Trace_Context()
    {
        using var parent = new Activity("parent");
        parent.SetIdFormat(ActivityIdFormat.W3C);
        parent.TraceStateString = "state=value";
        parent.Start();

        var traceContext = InvokeTelemetryHelper<(string? Traceparent, string? Tracestate)>("GetTraceContext");
        Assert.Equal(parent.Id, traceContext.Traceparent);
        Assert.Equal("state=value", traceContext.Tracestate);

        parent.Stop();
        using var restored = InvokeTelemetryHelper<Activity?>(
            "RestoreTraceContext",
            traceContext.Traceparent,
            traceContext.Tracestate);

        Assert.NotNull(restored);
        Assert.Equal(parent.Id, restored.ParentId);
        Assert.Equal("state=value", restored.TraceStateString);

        Assert.Null(InvokeTelemetryHelper<Activity?>("RestoreTraceContext", "not-a-traceparent", null));
    }

    private static T InvokeTelemetryHelper<T>(string name, params object?[] args)
    {
        var helperType = typeof(CopilotClient).Assembly.GetType("Microsoft.Copilot.TelemetryHelpers", throwOnError: true)!;
        var method = helperType.GetMethod(name, BindingFlags.Static | BindingFlags.NonPublic)!;
        return (T)method.Invoke(null, args)!;
    }
}
