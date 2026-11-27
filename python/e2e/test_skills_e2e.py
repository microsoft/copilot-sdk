"""
Tests for skills configuration functionality
"""

import os
import shutil

import pytest

from copilot.session import PermissionHandler
from copilot.session_events import SkillSource

from .testharness import E2ETestContext

pytestmark = pytest.mark.asyncio(loop_scope="module")

SKILL_MARKER = "PINEAPPLE_COCONUT_42"


@pytest.fixture(autouse=True)
def clean_skills_dir(ctx: E2ETestContext):
    """Ensure we start fresh each time"""
    skills_dir = os.path.join(ctx.work_dir, ".test_skills")
    if os.path.exists(skills_dir):
        shutil.rmtree(skills_dir)
    yield


def create_skill_dir(work_dir: str) -> str:
    """Create a skills directory in the working directory"""
    skills_dir = os.path.join(work_dir, ".test_skills")
    os.makedirs(skills_dir, exist_ok=True)

    # Create a skill subdirectory with SKILL.md
    skill_subdir = os.path.join(skills_dir, "test-skill")
    os.makedirs(skill_subdir, exist_ok=True)

    # Create a skill that instructs the model to include a specific marker in responses
    skill_content = f"""---
name: test-skill
description: A test skill that adds a marker to responses
---

# Test Skill Instructions

IMPORTANT: You MUST include the exact text "{SKILL_MARKER}" somewhere in EVERY response you give. \
This is a mandatory requirement. Include it naturally in your response.
""".replace("\r", "")
    with open(os.path.join(skill_subdir, "SKILL.md"), "w", newline="\n") as f:
        f.write(skill_content)

    return skills_dir


class TestSkillBehavior:
    async def test_should_load_and_apply_skill_from_skilldirectories(self, ctx: E2ETestContext):
        """Test that skills are loaded and applied from skillDirectories"""
        skills_dir = create_skill_dir(ctx.work_dir)
        session = await ctx.client.create_session(
            permissions={"on_request": PermissionHandler.approve_all},
            skills={"directories": [skills_dir]},
        )

        assert session.session_id is not None

        # The skill instructs the model to include a marker - verify it appears
        message = await session.send_and_wait("Say hello briefly using the test skill.")
        assert message is not None
        assert SKILL_MARKER in message.data.content

        await session.disconnect()

    async def test_should_control_ambient_project_skills_with_enableconfigdiscovery(
        self, ctx: E2ETestContext
    ):
        """Test that EnableConfigDiscovery toggles discovery of project-level skills.

        Project-level skills live under ``.github/skills`` in the working directory.
        """
        import uuid

        project_dir = os.path.join(ctx.work_dir, f"config-discovery-{uuid.uuid4().hex}")
        project_skills_dir = os.path.join(project_dir, ".github", "skills")
        skill_name = f"ambient-skill-{uuid.uuid4().hex}"[:32]
        os.makedirs(project_skills_dir, exist_ok=True)

        skill_subdir = os.path.join(project_skills_dir, skill_name)
        os.makedirs(skill_subdir, exist_ok=True)
        skill_content = (
            "---\n"
            f"name: {skill_name}\n"
            "description: A project skill discovered from .github/skills\n"
            "---\n"
            "\n"
            "Use the exact phrase AMBIENT_DISCOVERY_SKILL when this skill is active.\n"
        )
        with open(os.path.join(skill_subdir, "SKILL.md"), "w", newline="\n") as f:
            f.write(skill_content)

        # Disabled discovery: project skills should be hidden.
        disabled_session = await ctx.client.create_session(
            working_directory=project_dir,
            permissions={"on_request": PermissionHandler.approve_all},
            enable_config_discovery=False,
        )
        disabled_skills = await disabled_session.rpc.skills.list()
        assert not any(s.name == skill_name for s in disabled_skills.skills)
        await disabled_session.disconnect()

        # Enabled discovery: project skills should be present and active.
        enabled_session = await ctx.client.create_session(
            working_directory=project_dir,
            permissions={"on_request": PermissionHandler.approve_all},
            enable_config_discovery=True,
        )
        enabled_skills = await enabled_session.rpc.skills.list()
        discovered = [s for s in enabled_skills.skills if s.name == skill_name]
        assert len(discovered) == 1
        skill = discovered[0]
        assert skill.enabled is True
        assert skill.source == SkillSource.PROJECT
        assert skill.path.endswith(os.path.join(skill_name, "SKILL.md"))
        await enabled_session.disconnect()
