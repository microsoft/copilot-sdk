/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Test.Harness;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public class StreamingFidelityE2ETests(E2ETestFixture fixture, ITestOutputHelper output) : E2ETestBase(fixture, "streaming_fidelity", output)
{
    [Fact]
    // TODO(BYOK): Anthropic Messages emitted delta events with Streaming=false. Investigate the
    // native-client streaming contract before keeping this disabled for every BYOK backend.
    [Trait(E2ETestTraits.Backend, E2ETestTraits.CapiOnly)]
    public async Task Should_Not_Produce_Deltas_When_Streaming_Is_Disabled()
    {
        var session = await CreateSessionAsync(new CreateSessionOptions { Events = new SessionEventOptions { Streaming = false } });

        var events = new List<SessionEvent>();
        session.On<SessionEvent>(evt => { lock (events) { events.Add(evt); } });

        await session.SendAndWaitAsync(new MessageOptions { Prompt = "Say 'hello world'." });

        List<SessionEvent> snapshot;
        lock (events) { snapshot = [.. events]; }

        var deltaEvents = snapshot.OfType<AssistantMessageDeltaEvent>().ToList();

        // No deltas when streaming is off
        Assert.Empty(deltaEvents);

        // But should still have a final assistant.message
        var assistantEvents = snapshot.OfType<AssistantMessageEvent>().ToList();
        Assert.NotEmpty(assistantEvents);

        await session.DisposeAsync();
    }

    [Fact]
    public async Task Should_Emit_AssistantMessageStart_Before_Deltas_With_Matching_MessageId()
    {
        var session = await CreateSessionAsync(new CreateSessionOptions { Events = new SessionEventOptions { Streaming = true } });

        var events = new List<SessionEvent>();
        session.On<SessionEvent>(evt => { lock (events) { events.Add(evt); } });

        await session.SendAndWaitAsync(new MessageOptions { Prompt = "Count from 1 to 5, separated by commas." });

        List<SessionEvent> snapshot;
        lock (events) { snapshot = [.. events]; }

        var startEvents = snapshot.OfType<AssistantMessageStartEvent>().ToList();
        var deltaEvents = snapshot.OfType<AssistantMessageDeltaEvent>().ToList();
        var messageEvents = snapshot.OfType<AssistantMessageEvent>().ToList();

        Assert.NotEmpty(startEvents);
        Assert.NotEmpty(deltaEvents);
        Assert.NotEmpty(messageEvents);
        Assert.All(deltaEvents, delta => Assert.False(string.IsNullOrEmpty(delta.Data.DeltaContent)));
        Assert.True(snapshot.IndexOf(deltaEvents[0]) < snapshot.LastIndexOf(messageEvents[^1]),
            "Expected assistant.message_delta before the final assistant.message");

        // The start event must have a non-empty messageId
        var firstStart = startEvents[0];
        Assert.False(string.IsNullOrEmpty(firstStart.Data.MessageId));

        // The first message_start should arrive before the first message_delta
        var firstStartIdx = snapshot.IndexOf(firstStart);
        var firstDeltaIdx = snapshot.IndexOf(deltaEvents[0]);
        Assert.True(firstStartIdx < firstDeltaIdx,
            $"Expected assistant.message_start ({firstStartIdx}) before first assistant.message_delta ({firstDeltaIdx})");

        // Every assistant.message_start should have a corresponding assistant.message
        // emitted later with the same messageId.
        foreach (var start in startEvents)
        {
            Assert.Contains(messageEvents, m => m.Data.MessageId == start.Data.MessageId);
        }

        await session.DisposeAsync();
    }
}
