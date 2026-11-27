/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

#if NET
using System.Diagnostics;
using System.Collections.Concurrent;
using System.Text;
using Microsoft.Copilot.Test.Harness;
using Microsoft.Extensions.Logging;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.Unit;

public sealed class StdioShutdownTests(ITestOutputHelper output)
{
    [Theory]
    [InlineData("response")]
    [InlineData("RPC error")]
    [InlineData("EOF")]
    [InlineData("caller cancellation")]
    [InlineData("stop")]
    [InlineData("force-stop")]
    [InlineData("restart")]
    public Task Direct_Shutdown_Drains_Final_Reply_After_Observed_Child_Exit(string outcome) =>
        RunExitScenarioAsync(outcome);

    [Fact]
    public Task Unexpected_Exit_Fails_Ordinary_Request_When_Descendant_Holds_Stdout() =>
        RunExitScenarioAsync("unexpected exit");

    private async Task RunExitScenarioAsync(string outcome)
    {
        var unexpectedExit = outcome == "unexpected exit";
        var directory = Path.GetFullPath(Path.Combine("TestResults", $"copilot-shutdown-drain-{Guid.NewGuid():N}"));
        Directory.CreateDirectory(directory);
        var script = Path.Combine(E2ETestBase.FindTestHarnessDir(), "stdio-shutdown-runtime.cjs");
        var forwarder = Path.GetFullPath(Path.Combine(E2ETestBase.FindTestHarnessDir(),
            "..", "..", "dotnet", "test", "Harness", "stdio-shutdown-forwarder.cjs"));
        var marker = Path.Combine(directory, "telemetry.jsonl");
        var pidPath = Path.Combine(directory, "runtime.pid");
        var mode = outcome switch
        {
            "RPC error" => "shutdown-exit-error",
            "EOF" => "shutdown-exit-eof",
            "unexpected exit" => "unexpected-exit",
            _ => "shutdown-exit"
        };
        using var releaseCallback = new ManualResetEventSlim();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var returned = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var delivered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var restartDelivered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        ConcurrentQueue<string> lifecycleEvents = new();
        var logger = new ShutdownLogger();
        using var shutdownCancellation = new CancellationTokenSource();
        Process? process = null, inner = null, relay = null;
        Exception? bodyFailure = null;
        List<Exception> cleanupFailures = [];
        async Task CleanupAsync(Func<Task> cleanup)
        {
            try { await cleanup(); }
            catch (Exception failure) { cleanupFailures.Add(failure); }
        }

        try
        {
            await using var client = new CopilotClient(new CopilotClientOptions
            {
                Mode = CopilotClientMode.CodingAgent,
                Runtime = RuntimeConnection.ForSpawnedStdio(path: "node", args:
                    [forwarder, "runtime", script, marker, mode, pidPath]),
                GitHub = new ClientGitHubOptions { UseLoggedInUser = false },
                Observability = new ClientObservabilityOptions { Logger = logger }
            });
            using var subscription = client.OnLifecycle<SessionUpdatedEvent>(evt =>
            {
                if (evt.SessionId != "shutdown-drain") return;
                entered.TrySetResult();
                try
                {
                    releaseCallback.Wait();
                }
                finally
                {
                    returned.TrySetResult();
                }
                throw new InvalidOperationException("Intentional lifecycle callback failure");
            });
            using var observer = client.OnLifecycle<SessionUpdatedEvent>(evt =>
            {
                lifecycleEvents.Enqueue(evt.SessionId);
                if (evt.SessionId == "shutdown-drain-next") delivered.TrySetResult();
                if (evt.SessionId == "restart-drain") restartDelivered.TrySetResult();
            });
            try
            {
                // The runner's hang watchdog bounds the scenario, not an elapsed startup budget.
                await client.StartAsync();
                process = await ObserveProcessAsync(pidPath);
                inner = await ObserveProcessAsync($"{pidPath}.inner");
                relay = await ObserveProcessAsync($"{pidPath}.relay");
                Assert.False(process.HasExited, "The fixture exited before the shutdown request.");
                var exited = process.WaitForExitAsync();
                var innerExited = inner.WaitForExitAsync();
                Task shutdown = unexpectedExit
                    ? client.PingAsync("exit-without-reply")
                    : client.Rpc.Runtime.ShutdownAsync(shutdownCancellation.Token);

                await entered.Task;
                await innerExited.WaitAsync(TimeSpan.FromSeconds(5));
                await exited.WaitAsync(TimeSpan.FromSeconds(5));
                Assert.True(process.HasExited);
                Assert.True(inner.HasExited);
                Assert.False(relay.HasExited, "The forwarder must hold stdout open after the runtime exits.");
                output.WriteLine($"Observed child exit: pid={process.Id}; outcome={outcome}; callback held={!returned.Task.IsCompleted}");
                Assert.False(returned.Task.IsCompleted, "The consumer callback returned before the child exit.");
                var held = await File.ReadAllTextAsync($"{marker}.held");
                if (outcome == "EOF" || unexpectedExit) Assert.Empty(held);
                else Assert.Contains("\"id\":", held);
                Assert.False(shutdown.IsCompleted, "Shutdown settled before the final bytes or EOF were released.");

                if (unexpectedExit)
                {
                    var error = await Assert.ThrowsAnyAsync<IOException>(() => shutdown);
                    Assert.Equal("Communication error with Copilot CLI: The JSON-RPC connection was lost.", error.Message);
                    Assert.Equal("ConnectionLostException", error.InnerException?.GetType().Name);
                    Assert.False(relay.HasExited, "The connection must fail before the descendant closes stdout.");
                }
                else if (outcome == "caller cancellation")
                {
                    shutdownCancellation.Cancel();
                    var error = await Assert.ThrowsAnyAsync<OperationCanceledException>(
                        () => shutdown);
                    Assert.Equal(shutdownCancellation.Token, error.CancellationToken);
                }
                else
                {
                    await File.WriteAllTextAsync($"{marker}.release", "");
                    switch (outcome)
                    {
                        case "response":
                        case "stop":
                        case "force-stop":
                        case "restart":
                            await shutdown;
                            break;
                        case "RPC error":
                            var error = await Assert.ThrowsAnyAsync<Exception>(() => shutdown);
                            Assert.Contains("Intentional shutdown failure", error.Message);
                            break;
                        default:
                            var connectionError = await Assert.ThrowsAsync<IOException>(() => shutdown);
                            Assert.Contains("Communication error with Copilot CLI", connectionError.Message);
                            // EOF and the drain-timeout fallback fail the RPC with the same exception.
                            Assert.DoesNotContain("Copilot CLI stdout remained open after the shutdown timeout", logger.Messages);
                            break;
                    }
                }
                Assert.False(returned.Task.IsCompleted, "RPC settlement waited for the consumer callback.");
                Assert.Empty(lifecycleEvents);
                switch (outcome)
                {
                    case "stop":
                        await client.StopAsync().WaitAsync(TimeSpan.FromSeconds(5));
                        break;
                    case "force-stop":
                        await client.ForceStopAsync().WaitAsync(TimeSpan.FromSeconds(5));
                        break;
                    case "restart":
                        await client.StartAsync();
                        var ping = await client.PingAsync("restarted");
                        Assert.Equal("restarted", ping.Message);
                        Assert.Empty(lifecycleEvents);
                        break;
                    default:
                        releaseCallback.Set();
                        await delivered.Task;
                        Assert.Equal(["shutdown-drain", "shutdown-drain-next"], lifecycleEvents.ToArray());
                        break;
                }
                if (outcome is "stop" or "force-stop" or "restart")
                {
                    Assert.False(returned.Task.IsCompleted, "Connection retirement waited for the consumer callback.");
                    releaseCallback.Set();
                    await delivered.Task;
                    if (outcome == "restart")
                    {
                        await restartDelivered.Task;
                        Assert.Equal(["shutdown-drain", "shutdown-drain-next", "restart-drain"], lifecycleEvents.ToArray());
                    }
                    else
                    {
                        Assert.Equal(["shutdown-drain", "shutdown-drain-next"], lifecycleEvents.ToArray());
                    }
                }
            }
            finally
            {
                releaseCallback.Set();
                await CleanupAsync(() => File.WriteAllTextAsync($"{marker}.release", ""));
                if (entered.Task.IsCompleted)
                {
                    await CleanupAsync(() => returned.Task.WaitAsync(TimeSpan.FromSeconds(5)));
                }
                await CleanupAsync(() => client.ForceStopAsync().WaitAsync(TimeSpan.FromSeconds(5)));
                if (relay is not null)
                {
                    await CleanupAsync(() => relay.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5)));
                }
            }
        }
        catch (Exception failure)
        {
            bodyFailure = failure;
            throw;
        }
        finally
        {
            foreach (var child in new[] { relay, inner, process })
            {
                if (child is null) continue;
                await CleanupAsync(async () =>
                {
                    using (child)
                    {
                        if (!child.HasExited)
                        {
                            child.Kill(entireProcessTree: true);
                            await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
                        }
                    }
                });
            }
            await CleanupAsync(() =>
            {
                Directory.Delete(directory, recursive: true);
                return Task.CompletedTask;
            });
            if (bodyFailure is not null && cleanupFailures.Count > 0)
            {
                bodyFailure.Data["ShutdownFixtureCleanup"] =
                    new AggregateException("Shutdown fixture cleanup failed.", cleanupFailures);
            }
        }
        if (cleanupFailures.Count > 0)
        {
            throw new AggregateException("Shutdown fixture cleanup failed.", cleanupFailures);
        }
    }

    private sealed class ShutdownLogger : ILogger
    {
        public ConcurrentQueue<string> Messages { get; } = new();
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => true;
        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter) => Messages.Enqueue(formatter(state, exception));
    }

    private static async Task<Process> ObserveProcessAsync(string pidPath)
    {
        var process = Process.GetProcessById(
            int.Parse(await File.ReadAllTextAsync(pidPath), System.Globalization.CultureInfo.InvariantCulture));
        try
        {
            _ = process.Handle;
            Assert.False(process.HasExited, $"The process in {pidPath} exited before observation.");
            return process;
        }
        catch
        {
            process.Dispose();
            throw;
        }
    }

    [Theory]
    [InlineData("stop")]
    [InlineData("dispose")]
    [InlineData("force")]
    [InlineData("fallback")]
    [InlineData("start-failure")]
    public async Task Owned_Stdio_Runtime_Finishes_Host_Cleanup_Before_Graceful_Stop_Returns(string operation)
    {
        var directory = Path.Combine(Path.GetTempPath(), $"copilot-shutdown-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        var script = Path.Combine(E2ETestBase.FindTestHarnessDir(), "stdio-shutdown-runtime.cjs");
        var marker = Path.Combine(directory, "telemetry.jsonl");
        var pidPath = Path.Combine(directory, "runtime.pid");
        var tracePath = Path.Combine(directory, "startup-trace.jsonl");
        List<string> args = [script, marker, operation, pidPath];
        if (operation == "start-failure")
        {
            args.AddRange(["--fixture-trace", tracePath]);
        }

        try
        {
            using var observer = operation == "start-failure"
                ? new RuntimeStartupProcessObserver(pidPath, useStdio: true)
                : null;
            await using var client = new CopilotClient(new CopilotClientOptions
            {
                Mode = CopilotClientMode.CodingAgent,
                Runtime = RuntimeConnection.ForSpawnedStdio(path: "node", args: args),
                GitHub = new ClientGitHubOptions { UseLoggedInUser = false },
                Observability = new ClientObservabilityOptions { Logger = observer }
            });
            if (operation == "start-failure")
            {
                await WithFailureDiagnosticsAsync(async () =>
                {
                    // Failed startup includes owned-process cleanup, which has its own longer budgets.
                    var error = await Assert.ThrowsAsync<InvalidOperationException>(() => client.StartAsync());
                    Assert.Contains("protocol version mismatch", error.Message);
                    Assert.True(observer!.Process.HasExited, "Failed startup retained the runtime process.");
                    Assert.False(File.Exists(marker));
                }, pidPath, tracePath,
                    () => $"at failure: observed-pid={observer!.Process.Id}; has-exited={observer.Process.HasExited}",
                    output.WriteLine);
                return;
            }

            await client.StartAsync();
            using var process = Process.GetProcessById(
                int.Parse(await File.ReadAllTextAsync(pidPath), System.Globalization.CultureInfo.InvariantCulture));
            _ = process.Handle;
            var elapsed = Stopwatch.StartNew();

            switch (operation)
            {
                case "stop":
                    await client.StopAsync().WaitAsync(TimeSpan.FromSeconds(5));
                    break;
                case "dispose":
                    await client.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5));
                    break;
                case "fallback":
                    // Allow shutdown RPC, graceful exit, kill/reap, and stderr drain their separate budgets.
                    await client.StopAsync().WaitAsync(TimeSpan.FromSeconds(40));
                    // The 10s exit timer can fire a clock tick before Stopwatch reaches 10s.
                    Assert.True(elapsed.Elapsed >= TimeSpan.FromSeconds(9),
                        "Graceful stop must wait for its exit timeout before terminating the child.");
                    break;
                default:
                    await client.ForceStopAsync().WaitAsync(TimeSpan.FromSeconds(5));
                    break;
            }

            Assert.True(process.HasExited);
            await client.DisposeAsync();
            await client.DisposeAsync();
            if (operation == "force")
            {
                Assert.False(File.Exists(marker));
            }
            else
            {
                Assert.Equal("{\"type\":\"span\"}\n", await File.ReadAllTextAsync(marker));
            }
        }
        catch (Exception failure) when (operation == "start-failure")
        {
            await ReportFailureDiagnosticsAsync(failure, pidPath, tracePath,
                () => "after client cleanup; before fixture removal", output.WriteLine);
            throw;
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private static async Task WithFailureDiagnosticsAsync(
        Func<Task> action, string pidPath, string tracePath, Func<string> observe, Action<string> report)
    {
        try
        {
            await action();
        }
        catch (Exception failure)
        {
            await ReportFailureDiagnosticsAsync(failure, pidPath, tracePath, observe, report);
            throw;
        }
    }

    private static async Task ReportFailureDiagnosticsAsync(
        Exception failure, string pidPath, string tracePath, Func<string> observe, Action<string> report)
    {
        var diagnostic = new StringBuilder("stdio start-failure diagnostics\n");
        async Task ReadAsync(string label, string path)
        {
            try
            {
                var contents = await File.ReadAllTextAsync(path);
                diagnostic.Append(label).Append(": ").AppendLine(contents);
            }
            catch (Exception error)
            {
                diagnostic.Append(label).Append(": diagnostic-error=").AppendLine(error.GetType().Name);
            }
        }
        await ReadAsync("pid", pidPath);
        await ReadAsync("trace", tracePath);
        try
        {
            diagnostic.AppendLine(observe());
        }
        catch (Exception error)
        {
            diagnostic.Append("observer: diagnostic-error=").AppendLine(error.GetType().Name);
        }
        var text = diagnostic.ToString();
        failure.Data["StdioShutdownDiagnostics"] = text;
        try
        {
            report(text);
        }
        catch (Exception error)
        {
            failure.Data["StdioShutdownDiagnosticError"] = $"output: diagnostic-error={error.GetType().Name}";
        }
    }
}
#endif
