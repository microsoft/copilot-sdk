"""E2E tests for session lifecycle error handling."""

from __future__ import annotations

import pytest

from copilot.session import PermissionHandler

from .testharness import E2ETestContext

pytestmark = pytest.mark.asyncio(loop_scope="module")


class TestErrorResilience:
    async def test_should_throw_when_sending_to_disconnected_session(self, ctx: E2ETestContext):
        session = await ctx.client.create_session(
            permissions={"on_request": PermissionHandler.approve_all}
        )
        await session.disconnect()

        with pytest.raises(Exception):
            await session.send_and_wait("Hello")
