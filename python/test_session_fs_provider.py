# --------------------------------------------------------------------------------------------
#  Copyright (c) Microsoft Corporation. All rights reserved.
# --------------------------------------------------------------------------------------------

"""Unit tests for the session filesystem provider adapter."""

from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from copilot import SessionFsWriteFailure
from copilot.rpc import SessionFSSqliteExistsRequest
from copilot.session_fs_provider import (
    SessionFsProvider,
    SessionFsSqliteProvider,
    create_session_fs_adapter,
)


async def test_sqlite_exists_distinguishes_failure_from_absence():
    request = SessionFSSqliteExistsRequest(session_id="test-session")
    provider = Mock(spec=SessionFsSqliteProvider)
    provider.sqlite_exists = AsyncMock(side_effect=OSError("database unavailable"))
    adapter = create_session_fs_adapter(provider)

    with pytest.raises(OSError, match="database unavailable"):
        await adapter.sqlite_exists(request)

    provider.sqlite_exists = AsyncMock(return_value=False)
    assert not (await adapter.sqlite_exists(request)).exists


@pytest.mark.parametrize(
    ("failure", "changed"),
    [(RuntimeError("rejected"), None), (SessionFsWriteFailure("disk full"), True)],
)
async def test_failed_write_reports_only_known_changes(failure: Exception, changed: bool | None):
    provider = Mock(spec=SessionFsProvider)
    provider.write_file = AsyncMock(side_effect=failure)
    adapter = create_session_fs_adapter(provider)

    error = await adapter.write_file(SimpleNamespace(path="/file", content="data", mode=None))

    assert error is not None
    assert error.message == str(failure)
    assert error.write_changed is changed
    assert error.to_dict().get("writeChanged") is changed


async def test_write_marker_does_not_leak_into_other_methods():
    provider = Mock(spec=SessionFsProvider)
    provider.read_file = AsyncMock(side_effect=SessionFsWriteFailure("read failed"))
    adapter = create_session_fs_adapter(provider)

    result = await adapter.read_file(SimpleNamespace(path="/file"))

    assert result.error is not None
    assert result.error.write_changed is None
