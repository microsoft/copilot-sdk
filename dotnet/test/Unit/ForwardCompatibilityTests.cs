/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using Xunit;

namespace Microsoft.Copilot.Test.Unit;

/// <summary>
/// Tests for forward-compatible handling of unknown session event types.
/// Verifies that the SDK gracefully handles event types introduced by newer CLI versions.
/// </summary>
public class ForwardCompatibilityTests
{
    [Fact]
    public void UnknownEvent_InspectionAndWireSerialization_ReflectEdits()
    {
        var sessionEvent = SessionEvent.FromJson("""
        {
            "id": "00000000-0000-0000-0000-000000000003",
            "timestamp": "2026-01-01T00:00:00Z",
            "agentId": "original-agent",
            "type": "future.feature_from_server",
            "data": { "detail": "retained" },
            "extra": { "nullable": null },
            "discard": "remove before forwarding"
        }
        """);
        sessionEvent.AgentId = null;
        using var editedData = JsonDocument.Parse("""{ "detail": "edited" }""");
        sessionEvent.AdditionalProperties!["data"] = editedData.RootElement;
        sessionEvent.AdditionalProperties.Remove("discard");
        using var inspection = JsonDocument.Parse(sessionEvent.ToJson());
        Assert.Equal("edited", inspection.RootElement.GetProperty("data").GetProperty("detail").GetString());
        Assert.False(inspection.RootElement.TryGetProperty("agentId", out _));
        Assert.False(inspection.RootElement.TryGetProperty("discard", out _));
        var wire = CopilotClient.ToJsonElementForWire(sessionEvent)!.Value;
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(inspection.RootElement.GetRawText()), JsonNode.Parse(wire.GetRawText())));
        Assert.Equal("future.feature_from_server", wire.GetProperty("type").GetString());
        Assert.Equal(JsonValueKind.Null, wire.GetProperty("extra").GetProperty("nullable").ValueKind);
    }

    [Fact]
    public void UnknownEventType_IsSentToTheRuntimeUnchanged()
    {
        var json = """
        {
            "id": "00000000-0000-0000-0000-000000000003",
            "timestamp": "2026-01-01T00:00:00Z",
            "parentId": null,
            "type": "future.feature_from_server",
            "data": { "detail": [1, "two", { "three": true }] }
        }
        """;
        // The options the SDK uses for live session events received over JSON-RPC.
        var options = (JsonSerializerOptions)typeof(CopilotClient).GetProperty(
            "SerializerOptionsForMessageFormatter", BindingFlags.Static | BindingFlags.NonPublic)!
            .GetValue(null)!;

        foreach (var sessionEvent in new[] { SessionEvent.FromJson(json), JsonSerializer.Deserialize<SessionEvent>(json, options)! })
        {
            Assert.IsType<SessionEvent>(sessionEvent);
            var wire = CopilotClient.ToJsonElementForWire(sessionEvent)!.Value;

            Assert.Equal("future.feature_from_server", wire.GetProperty("type").GetString());
            Assert.Equal("00000000-0000-0000-0000-000000000003", wire.GetProperty("id").GetString());
            Assert.Equal(
                """{"detail":[1,"two",{"three":true}]}""",
                JsonNode.Parse(wire.GetProperty("data").GetRawText())!.ToJsonString());
        }
    }

    [Fact]
    public void FromJson_UnknownEventType_ReturnsBaseSessionEvent()
    {
        var json = """
        {
            "id": "12345678-1234-1234-1234-123456789abc",
            "timestamp": "2026-06-15T10:30:00Z",
            "parentId": "abcdefab-abcd-abcd-abcd-abcdefabcdef",
            "agentId": "future-agent",
            "type": "future.feature_from_server",
            "data": { "key": "value" }
        }
        """;

        var result = SessionEvent.FromJson(json);

        Assert.IsType<SessionEvent>(result);
        Assert.Equal("unknown", result.Type);
        Assert.Equal("future-agent", result.AgentId);
        Assert.Equal(Guid.Parse("12345678-1234-1234-1234-123456789abc"), result.Id);
        Assert.Equal(DateTimeOffset.Parse("2026-06-15T10:30:00Z"), result.Timestamp);
        Assert.Equal(Guid.Parse("abcdefab-abcd-abcd-abcd-abcdefabcdef"), result.ParentId);
    }

    [Fact]
    public void FromJson_KnownEventType_WithExtraUnknownFields_IgnoresExtras()
    {
        // Forward-compat: when the runtime adds new fields to a known event,
        // older SDK versions must ignore them and still successfully parse the event.
        var json = """
        {
            "id": "00000000-0000-0000-0000-000000000001",
            "timestamp": "2026-01-01T00:00:00Z",
            "parentId": null,
            "agentId": "agent-1",
            "type": "user.message",
            "futureEnvelopeField": {"someShape": [1,2,3]},
            "data": {
                "content": "Hello",
                "futureDataField": "ignored",
                "anotherFutureField": {"nested": true}
            }
        }
        """;

        var result = SessionEvent.FromJson(json);

        var msg = Assert.IsType<UserMessageEvent>(result);
        Assert.Equal("Hello", msg.Data.Content);
    }

    [Fact]
    public void FromJson_KnownEventType_WithExtraUnknownEnvelopeFields_IgnoresExtras()
    {
        // Pure envelope-level extra field (no inner data extras).
        var json = """
        {
            "id": "00000000-0000-0000-0000-000000000001",
            "timestamp": "2026-01-01T00:00:00Z",
            "parentId": null,
            "agentId": "agent-1",
            "type": "session.idle",
            "newServerOnlyField": 42,
            "data": {}
        }
        """;

        var result = SessionEvent.FromJson(json);

        Assert.IsType<SessionIdleEvent>(result);
        Assert.Equal("agent-1", result.AgentId);
    }

    [Fact]
    public void FromJson_InternalEventType_ReturnsBaseSessionEvent()
    {
        var json = """
            {
                "id": "12345678-1234-1234-1234-123456789abc",
                "timestamp": "2026-06-15T10:30:00Z",
                "type": "session.memory_changed",
                "data": {}
            }
            """;

        var result = SessionEvent.FromJson(json);

        Assert.IsType<SessionEvent>(result);
        Assert.Equal("unknown", result.Type);
    }

    [Fact]
    public void FromJson_KnownEventType_WithUnknownEnumInData_PreservesValue()
    {
        var json = """
        {
            "id": "00000000-0000-0000-0000-000000000001",
            "timestamp": "2026-01-01T00:00:00Z",
            "parentId": null,
            "type": "abort",
            "data": {
                "reason": "future_abort_reason"
            }
        }
        """;

        var result = SessionEvent.FromJson(json);

        var abort = Assert.IsType<AbortEvent>(result);
        Assert.Equal("future_abort_reason", abort.Data.Reason.Value);
    }

    [Fact]
    public void FromJson_KnownEventType_WithNonStringEnumInData_ThrowsJsonException()
    {
        var json = """
        {
            "id": "00000000-0000-0000-0000-000000000001",
            "timestamp": "2026-01-01T00:00:00Z",
            "parentId": null,
            "type": "abort",
            "data": {
                "reason": false
            }
        }
        """;

        var exception = Assert.Throws<JsonException>(() => SessionEvent.FromJson(json));
        Assert.Contains("AbortReason", exception.Message);
    }

    [Fact]
    public void RpcEnum_WithUnknownValue_PreservesValue()
    {
        var mode = JsonSerializer.Deserialize(
            """
            "future_mode"
            """,
            ForwardCompatibilityJsonContext.Default.SessionMode);

        Assert.Equal("future_mode", mode.Value);
        Assert.Equal(
            """
            "future_mode"
            """,
            JsonSerializer.Serialize(mode, ForwardCompatibilityJsonContext.Default.SessionMode));
    }

    [Fact]
    public void RpcEnum_WithNonStringValue_ThrowsJsonException()
    {
        var exception = Assert.Throws<JsonException>(() => JsonSerializer.Deserialize(
            """
            42
            """,
            ForwardCompatibilityJsonContext.Default.SessionMode));

        Assert.Contains("SessionMode", exception.Message);
    }

    [Fact]
    public void RpcEnum_DefaultValue_HasEmptyStringValue()
    {
        Microsoft.Copilot.SessionMode mode = default;

        Assert.Equal(string.Empty, mode.Value);
        Assert.Equal(string.Empty, mode.ToString());
    }

    [Fact]
    public void RpcEnum_DefaultValueSerialization_ThrowsJsonException()
    {
        Microsoft.Copilot.SessionMode mode = default;

        var exception = Assert.Throws<JsonException>(() => JsonSerializer.Serialize(
            mode,
            ForwardCompatibilityJsonContext.Default.SessionMode));

        Assert.Contains("SessionMode", exception.Message);
    }

    [Fact]
    public void FromJson_KnownEventType_WithNullOptionalFields_DoesNotThrow()
    {
        // The CLI may emit null for optional fields. Verify parsing doesn't throw.
        var json = """
        {
            "id": "00000000-0000-0000-0000-000000000001",
            "timestamp": "2026-01-01T00:00:00Z",
            "parentId": null,
            "agentId": null,
            "type": "user.message",
            "data": {
                "content": "Hello"
            }
        }
        """;

        var result = SessionEvent.FromJson(json);

        var msg = Assert.IsType<UserMessageEvent>(result);
        Assert.Null(msg.AgentId);
        Assert.Null(msg.ParentId);
        Assert.Equal("Hello", msg.Data.Content);
    }
}

[JsonSerializable(typeof(Microsoft.Copilot.SessionMode))]
internal partial class ForwardCompatibilityJsonContext : JsonSerializerContext;
