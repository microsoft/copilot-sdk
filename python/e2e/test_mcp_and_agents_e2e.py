"""
Tests for MCP servers and custom agents functionality
"""

import asyncio
import time
from pathlib import Path

import pytest

from copilot.session import MCPServerConfig, PermissionHandler
from copilot.session_events import McpServerStatus

from .testharness import E2ETestContext

TEST_MCP_SERVER = str(
    (Path(__file__).parents[2] / "test" / "harness" / "test-mcp-server.mjs").resolve()
)
TEST_HARNESS_DIR = str((Path(__file__).parents[2] / "test" / "harness").resolve())

pytestmark = pytest.mark.asyncio(loop_scope="module")


def _test_mcp_servers(*server_names: str) -> dict[str, MCPServerConfig]:
    return {
        server_name: {
            "command": "node",
            "args": [TEST_MCP_SERVER],
            "tools": ["*"],
            "working_directory": TEST_HARNESS_DIR,
        }
        for server_name in server_names
    }


async def _wait_for_mcp_server_status(
    session, server_name: str, expected_status: McpServerStatus = McpServerStatus.CONNECTED
) -> None:
    deadline = time.monotonic() + 60
    last_status = "<not listed>"

    while True:
        result = await session.rpc.mcp.list()
        server = next((s for s in result.servers if s.name == server_name), None)
        if server is not None and server.status == expected_status:
            return
        last_status = server.status if server is not None else "<not listed>"
        if time.monotonic() >= deadline:
            break
        await asyncio.sleep(0.2)

    raise AssertionError(
        f"{server_name} did not reach {expected_status.value}; last status was {last_status}"
    )


class TestMCPServers:
    async def test_should_accept_mcp_server_configuration_on_session_resume(
        self, ctx: E2ETestContext
    ):
        """Test that MCP server configuration is accepted on session resume"""
        session1 = await ctx.client.create_session(
            permissions={"on_request": PermissionHandler.approve_all}
        )
        session_id = session1.session_id

        # Resume with MCP servers
        mcp_servers = _test_mcp_servers("test-server")

        session2 = await ctx.client.resume_session(
            session_id,
            permissions={"on_request": PermissionHandler.approve_all},
            mcp={"servers": mcp_servers},
        )

        assert session2.session_id == session_id
        await _wait_for_mcp_server_status(session2, "test-server")

        await session2.disconnect()

    async def test_should_pass_literal_env_values_to_mcp_server_subprocess(
        self, ctx: E2ETestContext
    ):
        """Test that env values are passed as literals to MCP server subprocess"""
        mcp_servers: dict[str, MCPServerConfig] = {
            "env-echo": {
                "command": "node",
                "args": [TEST_MCP_SERVER],
                "tools": ["*"],
                "env": {"TEST_SECRET": "hunter2"},
                "working_directory": TEST_HARNESS_DIR,
            }
        }

        session = await ctx.client.create_session(
            permissions={"on_request": PermissionHandler.approve_all}, mcp={"servers": mcp_servers}
        )

        assert session.session_id is not None
        await _wait_for_mcp_server_status(session, "env-echo")

        message = await session.send_and_wait(
            "Use the env-echo/get_env tool to read the TEST_SECRET "
            "environment variable. Reply with just the value, nothing else."
        )
        assert message is not None
        assert "hunter2" in message.data.content

        await session.disconnect()
