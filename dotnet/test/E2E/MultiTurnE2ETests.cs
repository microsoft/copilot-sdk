/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

/// <summary>
/// Verifies that information produced in one turn (e.g., the contents of a file
/// just read or written) is available to subsequent turns in the same session.
/// Mirrors <c>nodejs/test/e2e/multi_turn.e2e.test.ts</c>.
/// </summary>
public class MultiTurnE2ETests(E2ETestFixture fixture, ITestOutputHelper output)
    : E2ETestBase(fixture, "multi_turn", output)
{

    [Fact]
    public async Task Should_Handle_File_Creation_Then_Reading_Across_Turns()
    {
        var session = await CreateSessionAsync();
        var events = new List<SessionEvent>();
        var eventsLock = new object();
        using var subscription = session.On<SessionEvent>(evt =>
        {
            lock (eventsLock)
            {
                events.Add(evt);
            }
        });

        // First turn: create a file
        await session.SendAndWaitAsync(new MessageOptions
        {
            Prompt = "Create a file called 'greeting.txt' with the content 'Hello from multi-turn test'.",
        });
        Assert.Equal("Hello from multi-turn test", await File.ReadAllTextAsync(Path.Join(Ctx.WorkDir, "greeting.txt")));
        AssertToolTurnOrdering(SnapshotAndClearEvents(events, eventsLock), "file creation turn");

        // Second turn: read the file
        var msg = await session.SendAndWaitAsync(new MessageOptions
        {
            Prompt = "Read the file 'greeting.txt' and tell me its exact contents.",
        });
        Assert.Contains("Hello from multi-turn test", msg?.Data.Content ?? string.Empty);
        AssertToolTurnOrdering(SnapshotAndClearEvents(events, eventsLock), "file read turn");
    }

    private static List<SessionEvent> SnapshotAndClearEvents(List<SessionEvent> events, object eventsLock)
    {
        lock (eventsLock)
        {
            var snapshot = events.ToList();
            events.Clear();
            return snapshot;
        }
    }

    private static void AssertToolTurnOrdering(IReadOnlyList<SessionEvent> events, string turnDescription)
    {
        var observedTypes = string.Join(", ", events.Select(e => e.Type));
        var userMessage = IndexOf<UserMessageEvent>(events);
        var toolStarts = events
            .Select((evt, index) => (evt, index))
            .Where(item => item.evt is ToolExecutionStartEvent)
            .Select(item => (Event: (ToolExecutionStartEvent)item.evt, item.index))
            .ToList();
        var toolCompletes = events
            .Select((evt, index) => (evt, index))
            .Where(item => item.evt is ToolExecutionCompleteEvent)
            .Select(item => (Event: (ToolExecutionCompleteEvent)item.evt, item.index))
            .ToList();

        Assert.True(userMessage >= 0, $"Expected user.message in {turnDescription}. Observed: {observedTypes}");
        Assert.NotEmpty(toolStarts);
        Assert.NotEmpty(toolCompletes);
        Assert.All(toolStarts, start =>
        {
            Assert.False(string.IsNullOrEmpty(start.Event.Data.ToolCallId));
            Assert.False(string.IsNullOrEmpty(start.Event.Data.ToolName));
        });
        Assert.All(toolCompletes, complete => Assert.False(string.IsNullOrEmpty(complete.Event.Data.ToolCallId)));

        var firstToolStartIndex = toolStarts.Min(item => item.index);
        Assert.True(userMessage < firstToolStartIndex, $"Expected user.message before first tool start in {turnDescription}. Observed: {observedTypes}");

        foreach (var (complete, completeIndex) in toolCompletes)
        {
            var matchingStart = toolStarts.LastOrDefault(start =>
                start.Event.Data.ToolCallId == complete.Data.ToolCallId && start.index < completeIndex);
            Assert.NotNull(matchingStart.Event);
        }

        var lastToolCompleteIndex = toolCompletes.Max(item => item.index);
        var assistantAfterTools = IndexOf<AssistantMessageEvent>(events, lastToolCompleteIndex + 1);
        var sessionIdle = IndexOf<SessionIdleEvent>(events, Math.Max(assistantAfterTools + 1, 0));

        Assert.True(assistantAfterTools >= 0, $"Expected assistant.message after tool completion in {turnDescription}. Observed: {observedTypes}");
        Assert.True(sessionIdle >= 0, $"Expected session.idle after assistant.message in {turnDescription}. Observed: {observedTypes}");
        Assert.True(lastToolCompleteIndex < assistantAfterTools, $"Expected final tool completion before final assistant message in {turnDescription}. Observed: {observedTypes}");
        Assert.True(assistantAfterTools < sessionIdle, $"Expected final assistant message before idle in {turnDescription}. Observed: {observedTypes}");
    }

    private static int IndexOf<T>(IReadOnlyList<SessionEvent> events, int startIndex = 0)
    {
        for (var i = Math.Max(startIndex, 0); i < events.Count; i++)
        {
            if (events[i] is T)
            {
                return i;
            }
        }

        return -1;
    }
}
