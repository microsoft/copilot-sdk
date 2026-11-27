"""E2E Streaming Fidelity Tests"""

import pytest

from copilot.session import PermissionHandler

from .testharness import E2ETestContext

pytestmark = pytest.mark.asyncio(loop_scope="module")


class TestStreamingFidelity:
    async def test_should_produce_delta_events_when_streaming_is_enabled(self, ctx: E2ETestContext):
        session = await ctx.client.create_session(
            permissions={"on_request": PermissionHandler.approve_all}, events={"streaming": True}
        )

        events = []
        session.on(lambda event: events.append(event))

        await session.send_and_wait("Count from 1 to 5, separated by commas.")

        types = [e.type.value for e in events]

        # Should have streaming deltas before the final message
        delta_events = [e for e in events if e.type.value == "assistant.message_delta"]
        assert len(delta_events) >= 1

        # Deltas should have content
        for delta in delta_events:
            delta_content = getattr(delta.data, "delta_content", None)
            assert delta_content is not None
            assert isinstance(delta_content, str)

        # Should still have a final assistant.message
        assert "assistant.message" in types

        # Deltas should come before the final message
        first_delta_idx = types.index("assistant.message_delta")
        last_assistant_idx = len(types) - 1 - types[::-1].index("assistant.message")
        assert first_delta_idx < last_assistant_idx

        await session.disconnect()

    async def test_should_not_produce_deltas_when_streaming_is_disabled(self, ctx: E2ETestContext):
        session = await ctx.client.create_session(
            permissions={"on_request": PermissionHandler.approve_all}, events={"streaming": False}
        )

        events = []
        session.on(lambda event: events.append(event))

        await session.send_and_wait("Say 'hello world'.")

        delta_events = [e for e in events if e.type.value == "assistant.message_delta"]

        # No deltas when streaming is off
        assert len(delta_events) == 0

        # But should still have a final assistant.message
        assistant_events = [e for e in events if e.type.value == "assistant.message"]
        assert len(assistant_events) >= 1

        await session.disconnect()
