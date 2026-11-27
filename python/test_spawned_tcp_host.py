"""TCP dial targets for client-owned and externally owned runtimes."""

import sys
import unittest
from unittest.mock import patch

from copilot import CopilotClient, RuntimeConnection

ANNOUNCE_PORT = ("-c", "print('listening on port 49123', flush=True)")


class TestSpawnedTcpHost(unittest.IsolatedAsyncioTestCase):
    async def assert_spawn_dials(self, client, host):
        try:
            await client._start_cli_server()
            with patch("socket.create_connection", side_effect=OSError("dial intercepted")) as dial:
                with self.assertRaisesRegex(RuntimeError, "dial intercepted"):
                    await client._connect_via_tcp()
            self.assertEqual(dial.call_args.args[0], (host, 49123))
        finally:
            await client.force_stop()

    async def test_spawned_host_arguments_select_the_dial_target(self):
        cases = [
            ([], "127.0.0.1"),
            (["--host", "::1"], "::1"),
            (["--host=127.0.0.1", "--host", "::1"], "::1"),
            (["--host", "::1", "--host=127.0.0.1"], "127.0.0.1"),
            (["--host=::1", "--host"], "::1"),
            (["--host", "0.0.0.0"], "127.0.0.1"),
            (["--host=::"], "::1"),
            (["--host=0:0:0:0:0:0:0:0"], "::1"),
            (["--host=bind.example.invalid"], "bind.example.invalid"),
        ]
        for args, host in cases:
            with self.subTest(args=args):
                original_args = args.copy()
                connection = RuntimeConnection.for_spawned_tcp(
                    path=sys.executable, args=(*ANNOUNCE_PORT, *args)
                )
                original_connection_args = connection.args
                client = CopilotClient(mode="coding-agent", runtime=connection)
                await self.assert_spawn_dials(client, host)
                self.assertEqual(args, original_args)
                self.assertEqual(connection.args, original_connection_args)

    async def test_external_host_is_not_rewritten(self):
        for host in ["localhost", "::1", "0.0.0.0", "::", "0:0:0:0:0:0:0:0"]:
            with self.subTest(host=host):
                connection = RuntimeConnection.for_tcp(host, 49124)
                client = CopilotClient(mode="coding-agent", runtime=connection)
                with patch(
                    "socket.create_connection", side_effect=OSError("dial intercepted")
                ) as dial:
                    with self.assertRaisesRegex(RuntimeError, "dial intercepted"):
                        await client._connect_via_tcp()
                self.assertEqual(dial.call_args.args[0], (host, 49124))
                self.assertEqual(connection.host, host)
