"""
E2E tests for session abort functionality.

Verifies that session.abort() cleanly interrupts an active turn during tool
execution without leaving dangling state or causing exceptions in the event
delivery pipeline.

Mirrors dotnet/test/E2E/AbortE2ETests.cs (snapshot category ``abort``).
"""

from __future__ import annotations

import asyncio

import pytest

from copilot.session import PermissionHandler
from copilot.tools import Tool, ToolInvocation, ToolResult

from .testharness import E2ETestContext

pytestmark = pytest.mark.asyncio(loop_scope="module")


class TestAbort:
    async def test_should_abort_during_active_tool_execution(self, ctx: E2ETestContext):
        tool_started: asyncio.Future = asyncio.get_event_loop().create_future()
        release_tool: asyncio.Future = asyncio.get_event_loop().create_future()

        async def slow_tool_handler(invocation: ToolInvocation) -> ToolResult:
            value = (invocation.arguments or {}).get("value", "")
            if not tool_started.done():
                tool_started.set_result(value)
            result = await release_tool
            return ToolResult(text_result_for_llm=str(result))

        session = await ctx.client.create_session(
            permissions={"on_request": PermissionHandler.approve_all},
            tools={
                "definitions": [
                    Tool(
                        name="slow_analysis",
                        description="A slow analysis tool that blocks until released",
                        parameters={
                            "type": "object",
                            "properties": {
                                "value": {"type": "string", "description": "Value to analyze"}
                            },
                            "required": ["value"],
                        },
                        handler=slow_tool_handler,
                    )
                ]
            },
        )

        cancellation_notice: asyncio.Future = asyncio.get_event_loop().create_future()

        def check_cancellation(event):
            if (
                event.type.value == "session.info"
                and event.data.info_type == "cancellation"
                and not cancellation_notice.done()
            ):
                cancellation_notice.set_result(event.data.message)

        unsubscribe_cancellation = session.on(check_cancellation)
        send_task = asyncio.create_task(
            session.send("Use slow_analysis with value 'test_abort'. Wait for the result.")
        )
        try:
            tool_value = await asyncio.wait_for(tool_started, timeout=60.0)
            assert tool_value == "test_abort"
            await asyncio.wait_for(send_task, timeout=60.0)

            # Abort while the tool is running and observe cancellation before releasing it.
            await session.abort()
            notice = await asyncio.wait_for(cancellation_notice, timeout=10.0)
            assert notice == "Operation cancelled by user"

            # Release the tool so its task doesn't leak
            if not release_tool.done():
                release_tool.set_result("RELEASED_AFTER_ABORT")

            # Session should be usable after abort
            recovery_received: asyncio.Future = asyncio.get_event_loop().create_future()

            def check_recovery(event):
                if (
                    event.type.value == "assistant.message"
                    and "tool_abort_recovery_ok" in (event.data.content or "").lower()
                    and not recovery_received.done()
                ):
                    recovery_received.set_result(event)

            unsubscribe = session.on(check_recovery)
            try:
                await session.send("Say 'tool_abort_recovery_ok'.")
                recovery_message = await asyncio.wait_for(recovery_received, timeout=60.0)
                assert "tool_abort_recovery_ok" in (recovery_message.data.content or "").lower()
            finally:
                unsubscribe()
        finally:
            unsubscribe_cancellation()
            if not release_tool.done():
                release_tool.set_result("CLEANUP")
            send_task.cancel()
            await asyncio.gather(send_task, return_exceptions=True)
            await session.disconnect()
