"""Tests for OpenTelemetry telemetry helpers."""

from __future__ import annotations

import asyncio
import sys
from unittest.mock import patch

import pytest

from copilot import CopilotClient, RuntimeConnection
from copilot._telemetry import get_trace_context, trace_context
from copilot.session import CopilotSession


class TestGetTraceContext:
    def test_returns_empty_dict_when_otel_not_installed(self):
        """get_trace_context() returns {} when opentelemetry is not importable."""
        real_import = __import__

        def _block_otel(name: str, *args, **kwargs):
            if name.startswith("opentelemetry"):
                raise ImportError("mocked")
            return real_import(name, *args, **kwargs)

        with patch("builtins.__import__", side_effect=_block_otel):
            result = get_trace_context()

        assert result == {}


class TestTraceContext:
    def test_yields_without_error_when_otel_not_installed(self):
        """trace_context() should gracefully yield even if opentelemetry is missing."""
        real_import = __import__

        def _block_otel(name: str, *args, **kwargs):
            if name.startswith("opentelemetry"):
                raise ImportError("mocked")
            return real_import(name, *args, **kwargs)

        with patch("builtins.__import__", side_effect=_block_otel):
            with trace_context("00-abc-def-01", None):
                pass  # should not raise


@pytest.mark.asyncio
@pytest.mark.parametrize("async_handler", [False, True])
@pytest.mark.parametrize("fails", [False, True])
async def test_hook_restores_runtime_trace_context(async_handler: bool, fails: bool):
    ambient = {
        "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        "tracestate": "vendor=ambient",
    }
    runtime = {
        "traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        "tracestate": "congo=t61rcWkgMzE",
    }
    captured = []
    output = {"additionalContext": "hook completed"}

    def handler(_input, _invocation):
        captured.append(get_trace_context())
        if fails:
            raise RuntimeError("hook failed")
        return output

    async def awaited_handler(input_data, invocation):
        captured.append(get_trace_context())
        await asyncio.sleep(0)
        return handler(input_data, invocation)

    session = CopilotSession("hook-session", None)
    session._register_hooks({"on_subagent_start": awaited_handler if async_handler else handler})
    with trace_context(ambient["traceparent"], ambient["tracestate"]):
        result = await session._handle_hooks_invoke("subagentStart", runtime)
        assert get_trace_context() == ambient

    assert captured == [runtime] * (2 if async_handler else 1)
    assert result == (None if fails else output)


class TestTelemetryConfig:
    @pytest.mark.asyncio
    @pytest.mark.parametrize("capture,mode", [(True, "SPAN_ONLY"), (False, "NO_CONTENT")])
    async def test_explicit_capture_overrides_inherited_mode(self, capture: bool, mode: str):
        client = CopilotClient(
            mode="coding-agent",
            runtime=RuntimeConnection.for_spawned_stdio(
                path=sys.executable,
                env={"OTEL_INSTRUMENTATION_GENAI_CAPTURE_CONTENT": "SPAN_AND_EVENT"},
            ),
            observability={"telemetry": {"capture_content": capture}},
        )
        with patch(
            "copilot.client.subprocess.Popen", side_effect=OSError("spawn stopped")
        ) as spawn:
            with pytest.raises(OSError, match="spawn stopped"):
                await client._start_cli_server()

        env = spawn.call_args.kwargs["env"]
        assert env["OTEL_INSTRUMENTATION_GENAI_CAPTURE_CONTENT"] == mode
        assert env["OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT"] == str(capture).lower()
