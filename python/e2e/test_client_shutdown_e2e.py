# Copyright (c) Microsoft Corporation. All rights reserved.

"""Python handler ownership across real runtime shutdown."""

import asyncio
import os
import signal

import pytest

from copilot.rpc import (
    HistoryImportConversationEvent,
    HistoryImportConversationEventData,
    HistoryImportConversationEventType,
    SessionHistoryImportConversationRequest,
    ToolsExecuteRequest,
)
from copilot.session import PermissionHandler
from copilot.session_events import UserMessageData
from copilot.tools import Tool, ToolResult

from .testharness import E2ETestContext

pytestmark = pytest.mark.asyncio(loop_scope="module")


async def test_stop_detaches_both_real_sessions_before_releasing_either_reply(
    ctx: E2ETestContext, monkeypatch: pytest.MonkeyPatch
):
    client = ctx.client
    session_ids: set[str] = set()
    for _ in range(2):
        session = await client.create_session(
            working_directory=ctx.work_dir,
            enable_config_discovery=False,
            permissions={"on_request": PermissionHandler.approve_all},
        )
        session_ids.add(session.session_id)
        await session.rpc.history.import_conversation(
            SessionHistoryImportConversationRequest(
                events=[
                    HistoryImportConversationEvent(
                        data=HistoryImportConversationEventData(content=session.session_id),
                        type=HistoryImportConversationEventType.USER_MESSAGE,
                    )
                ]
            )
        )
    assert len(session_ids) == 2
    process = client._process
    transport = client._client
    assert process is not None
    assert transport is not None
    request = transport.request
    detached: set[str] = set()
    both_detached = asyncio.Event()

    async def hold_detach_reply(method, params=None, timeout=None, **kwargs):
        reply = await request(method, params, timeout, **kwargs)
        if method == "session.detach":
            assert params is not None
            assert reply["success"] is True
            detached.add(params["sessionId"])
            if detached == session_ids:
                both_detached.set()
            await both_detached.wait()
        return reply

    # Forward every request to the live runtime; delay only delivery of detach replies.
    with monkeypatch.context() as patch:
        patch.setattr(transport, "request", hold_detach_reply)
        await client.stop()
    assert detached == session_ids
    assert process.poll() == 0

    await client.start()
    assert client._process is not None
    assert client._process is not process
    for session_id in sorted(session_ids):
        resumed = await client.resume_session(
            session_id,
            working_directory=ctx.work_dir,
            enable_config_discovery=False,
            permissions={"on_request": PermissionHandler.approve_all},
        )
        assert resumed.session_id == session_id
        assert [
            event.data.content
            for event in await resumed.get_events()
            if isinstance(event.data, UserMessageData)
        ] == [session_id]


@pytest.mark.parametrize(
    ("shutdown", "ownership"),
    [
        ("stop", "awaited"),
        ("force_stop", "awaited"),
        ("stop", "awaited_task"),
        ("stop", "gather"),
        ("stop", "wait_for"),
        ("stop", "wait_for_gather"),
        ("force_stop", "scheduled"),
        ("stop", "scheduled_gather"),
    ],
)
async def test_tool_shutdown_finishes_its_handler_and_retires_the_real_host(
    ctx: E2ETestContext, shutdown: str, ownership: str
):
    client = ctx.client
    unrelated_started = asyncio.Event()
    unrelated_stopped = asyncio.Event()
    handler_outcome: asyncio.Future[str] = asyncio.get_running_loop().create_future()
    shutdown_finished = asyncio.Event()
    independent_work = asyncio.Event()
    invocations = []
    shutdown_tasks = []

    async def unrelated(_invocation):
        unrelated_started.set()
        try:
            await asyncio.Event().wait()
        finally:
            unrelated_stopped.set()

    async def shutdown_handler(_invocation):
        async def shutdown_client():
            if ownership == "scheduled_gather":
                await asyncio.gather(client.stop())
            else:
                await getattr(client, shutdown)()
            shutdown_finished.set()

        try:
            if ownership in ("scheduled", "scheduled_gather"):
                shutdown_tasks.append(asyncio.create_task(shutdown_client()))
                await independent_work.wait()
            elif ownership == "wait_for":
                await asyncio.wait_for(shutdown_client(), timeout=float("inf"))
            elif ownership == "wait_for_gather":
                await asyncio.wait_for(asyncio.gather(client.stop()), timeout=float("inf"))
                shutdown_finished.set()
            elif ownership == "awaited_task":
                await asyncio.create_task(shutdown_client())
            elif ownership == "gather":
                await asyncio.gather(client.stop())
                shutdown_finished.set()
            else:
                await shutdown_client()
            handler_outcome.set_result("finished")
            return ToolResult(text_result_for_llm="stopped")
        except asyncio.CancelledError:
            handler_outcome.set_result("cancelled")
            raise

    try:
        sessions = []
        for name, handler in [("unrelated", unrelated), ("shutdown", shutdown_handler)]:
            session = await client.create_session(
                working_directory=ctx.work_dir,
                enable_config_discovery=False,
                permissions={"on_request": PermissionHandler.approve_all},
                tools={"definitions": [Tool(name, "Exercise client shutdown", handler)]},
            )
            await session.rpc.tools.initialize_and_validate()
            sessions.append(session)
        host = client._ffi_host
        process = client._process
        assert process is not None
        assert process.poll() is None
        invocations.append(
            asyncio.create_task(
                sessions[0].rpc.tools.execute(ToolsExecuteRequest(name="unrelated", arguments={}))
            )
        )
        await asyncio.wait_for(unrelated_started.wait(), timeout=10)
        invocations.append(
            asyncio.create_task(
                sessions[1].rpc.tools.execute(ToolsExecuteRequest(name="shutdown", arguments={}))
            )
        )
        outcome = await asyncio.wait_for(asyncio.shield(handler_outcome), timeout=30)
        scheduled = ownership in ("scheduled", "scheduled_gather")
        assert outcome == ("cancelled" if scheduled else "finished")
        await asyncio.wait_for(shutdown_finished.wait(), timeout=10)
        await asyncio.wait_for(unrelated_stopped.wait(), timeout=10)
        await asyncio.gather(*shutdown_tasks)
        await asyncio.gather(*invocations, return_exceptions=True)

        with pytest.raises(RuntimeError):
            _ = client.rpc
        if shutdown == "force_stop" and host is None:
            assert process.poll() == (-signal.SIGKILL if os.name == "posix" else 1)
        else:
            assert process.poll() == 0
        if host is not None:
            assert host._connection_id == 0
            assert host._server_id == 0
        assert client._ffi_host is None
        assert client._process is None
    finally:
        independent_work.set()
        await client.force_stop()
        await asyncio.gather(*shutdown_tasks, return_exceptions=True)
        await asyncio.gather(*invocations, return_exceptions=True)


@pytest.mark.parametrize(
    ("failure_timing", "continuation"),
    [
        ("before_shutdown", "independent"),
        ("during_detach", "independent"),
        ("during_detach", "shield"),
        ("during_detach", "task-group"),
    ],
)
async def test_failed_gather_preserves_only_the_awaited_shutdown(
    ctx: E2ETestContext, monkeypatch: pytest.MonkeyPatch, failure_timing: str, continuation: str
):
    client = ctx.client
    shutdown_finished = asyncio.Event()
    independent_started = asyncio.Event()
    independent_work = asyncio.Event()
    handler_stopped = asyncio.Event()
    detach_started = asyncio.Event()
    handler_tasks = []
    shutdown_tasks = []
    invocations = []

    async def fail():
        if failure_timing == "during_detach":
            await detach_started.wait()
        raise RuntimeError("gather child failed")

    async def shutdown_client():
        task = asyncio.current_task()
        assert task is not None
        shutdown_tasks.append(task)
        if failure_timing == "before_shutdown":
            await independent_started.wait()
        await client.stop()
        shutdown_finished.set()

    async def handler(_invocation):
        task = asyncio.current_task()
        assert task is not None
        handler_tasks.append(task)
        try:
            failed = asyncio.create_task(fail())
            if failure_timing == "before_shutdown":
                with pytest.raises(RuntimeError, match="gather child failed"):
                    await failed
            with pytest.raises(RuntimeError, match="gather child failed"):
                await asyncio.gather(failed, shutdown_client())
            if continuation == "shield":
                independent_started.set()
                await asyncio.shield(shutdown_tasks[0])
            elif continuation == "task-group":

                async def join_shutdown():
                    independent_started.set()
                    await shutdown_tasks[0]

                async with asyncio.TaskGroup() as group:
                    group.create_task(join_shutdown())
            else:
                independent_started.set()
                await independent_work.wait()
            return ToolResult(text_result_for_llm="released")
        finally:
            handler_stopped.set()

    try:
        session = await client.create_session(
            working_directory=ctx.work_dir,
            enable_config_discovery=False,
            permissions={"on_request": PermissionHandler.approve_all},
            tools={"definitions": [Tool("shutdown", "Exercise failed gather shutdown", handler)]},
        )
        await session.rpc.tools.initialize_and_validate()
        host = client._ffi_host
        process = client._process
        transport = client._client
        assert process is not None
        assert transport is not None
        assert process.poll() is None
        request = transport.request

        async def hold_detach_request(method, params=None, timeout=None, **kwargs):
            if failure_timing == "during_detach" and method == "session.detach":
                detach_started.set()
                await independent_started.wait()
            return await request(method, params, timeout, **kwargs)

        monkeypatch.setattr(transport, "request", hold_detach_request)
        invocations.append(
            asyncio.create_task(
                session.rpc.tools.execute(ToolsExecuteRequest(name="shutdown", arguments={}))
            )
        )
        await independent_started.wait()
        await asyncio.gather(*shutdown_tasks)
        assert shutdown_finished.is_set()
        assert independent_started.is_set()
        assert len(handler_tasks) == 1
        handler_task = handler_tasks[0]
        if continuation in ("shield", "task-group"):
            await handler_task
            assert handler_task.cancelling() == 0, "shutdown cancelled its awaiting handler"
        else:
            assert handler_task.cancelling() > 0, (
                "shutdown left independent handler work uncancelled"
            )
            with pytest.raises(asyncio.CancelledError):
                await handler_task
        assert handler_stopped.is_set()
        with pytest.raises(RuntimeError):
            _ = client.rpc
        assert process.poll() == 0
        if host is not None:
            assert host._connection_id == 0
            assert host._server_id == 0
        assert client._ffi_host is None
        assert client._process is None
    finally:
        independent_work.set()
        await client.force_stop()
        await asyncio.gather(*handler_tasks, *shutdown_tasks, *invocations, return_exceptions=True)
