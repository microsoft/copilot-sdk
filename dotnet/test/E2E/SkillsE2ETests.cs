/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public class SkillsE2ETests : E2ETestBase
{
    private const string SkillMarker = "PINEAPPLE_COCONUT_42";

    private readonly string _workDir;

    public SkillsE2ETests(E2ETestFixture fixture, ITestOutputHelper output) : base(fixture, "skills", output)
    {
        _workDir = fixture.Ctx.WorkDir;

        var skillsDir = Path.Join(_workDir, ".test_skills");
        if (Directory.Exists(skillsDir))
        {
            Directory.Delete(skillsDir, recursive: true);
        }
    }

    private string CreateSkillDir()
    {
        var skillsDir = Path.Join(_workDir, ".test_skills");
        Directory.CreateDirectory(skillsDir);

        // Create a skill subdirectory with SKILL.md
        var skillSubdir = Path.Join(skillsDir, "test-skill");
        Directory.CreateDirectory(skillSubdir);

        // Create a skill that instructs the model to include a specific marker in responses
        var skillContent = $@"---
name: test-skill
description: A test skill that adds a marker to responses
---

# Test Skill Instructions

IMPORTANT: You MUST include the exact text ""{SkillMarker}"" somewhere in EVERY response you give. This is a mandatory requirement. Include it naturally in your response.
".ReplaceLineEndings("\n");
        File.WriteAllText(Path.Join(skillSubdir, "SKILL.md"), skillContent);

        return skillsDir;
    }

    private static void CreateSkill(string skillsDir, string name, string description, string body)
    {
        var skillSubdir = Path.Join(skillsDir, name);
        Directory.CreateDirectory(skillSubdir);

        var skillContent = $"""
            ---
            name: {name}
            description: {description}
            ---

            {body}

            """.ReplaceLineEndings("\n");
        File.WriteAllText(Path.Join(skillSubdir, "SKILL.md"), skillContent);
    }

    [Fact]
    public async Task Should_Load_And_Apply_Skill_From_SkillDirectories()
    {
        var skillsDir = CreateSkillDir();
        var session = await CreateSessionAsync(new CreateSessionOptions
        { Skills = new SessionSkillOptions { Directories = [skillsDir] } });

        Assert.Matches(@"^[a-f0-9-]+$", session.SessionId);

        // The skill instructs the model to include a marker - verify it appears
        var message = await session.SendAndWaitAsync(new MessageOptions { Prompt = "Say hello briefly using the test skill." });
        Assert.NotNull(message);
        Assert.Contains(SkillMarker, message!.Data.Content);

        await session.DisposeAsync();
    }

    [Fact]
    public async Task Should_Control_Ambient_Project_Skills_With_EnableConfigDiscovery()
    {
        var projectDir = Path.Join(_workDir, $"config-discovery-{Guid.NewGuid():N}");
        var projectSkillsDir = Path.Join(projectDir, ".github", "skills");
        var skillName = $"ambient-skill-{Guid.NewGuid():N}".Substring(0, 32);
        Directory.CreateDirectory(projectSkillsDir);
        CreateSkill(
            projectSkillsDir,
            skillName,
            "A project skill discovered from .github/skills",
            "Use the exact phrase AMBIENT_DISCOVERY_SKILL when this skill is active.");

        var disabledSession = await CreateSessionAsync(new CreateSessionOptions
        { WorkingDirectory = projectDir, EnableConfigDiscovery = false });
        var disabledSkills = await disabledSession.Rpc.Skills.ListAsync();
        Assert.DoesNotContain(disabledSkills.Skills, skill => string.Equals(skill.Name, skillName, StringComparison.Ordinal));
        await disabledSession.DisposeAsync();

        var enabledSession = await CreateSessionAsync(new CreateSessionOptions
        { WorkingDirectory = projectDir, EnableConfigDiscovery = true });
        var enabledSkills = await enabledSession.Rpc.Skills.ListAsync();
        var discoveredSkill = Assert.Single(enabledSkills.Skills, skill => string.Equals(skill.Name, skillName, StringComparison.Ordinal));
        Assert.True(discoveredSkill.Enabled);
        Assert.Equal(SkillSource.Project, discoveredSkill.Source);
        Assert.EndsWith(Path.Join(skillName, "SKILL.md"), discoveredSkill.Path);
        await enabledSession.DisposeAsync();
    }
}
