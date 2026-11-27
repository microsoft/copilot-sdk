/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Rpc;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public class ScenarioTestingSkillsAndAgentsE2ETests(E2ETestFixture fixture, ITestOutputHelper output)
    : ScenarioTestingE2ETestBase(fixture, "scenario_testing_skills_and_agents", output)
{
    [Fact]
    public async Task Should_Reload_Atomically_Replaced_Skill_And_Replay_It_On_Resume()
    {
        const string skillName = "scenario-reloadable-skill";
        var skillsDirectory = Path.Join(Ctx.WorkDir, "scenario-skills", Guid.NewGuid().ToString("N"));
        var skillFile = WriteSkill(
            skillsDirectory,
            skillName,
            "Scenario skill version one.",
            "Use SCENARIO_SKILL_VERSION_ONE.");

        await using var session1 = await CreateSessionAsync(new CreateSessionOptions
        { Skills = new SessionSkillOptions { Directories = [skillsDirectory] } });

        AssertSkill(
            await session1.Rpc.Skills.ListAsync(),
            skillName,
            "Scenario skill version one.",
            skillFile);

        var replacement = Path.Join(Path.GetDirectoryName(skillFile)!, "SKILL.replacement.md");
        File.WriteAllText(
            replacement,
            CreateSkillContent(
                skillName,
                "Scenario skill version two.",
                "Use SCENARIO_SKILL_VERSION_TWO."));
        File.Replace(replacement, skillFile, destinationBackupFileName: null);

        await session1.Rpc.Skills.ReloadAsync();
        AssertSkill(
            await session1.Rpc.Skills.ListAsync(),
            skillName,
            "Scenario skill version two.",
            skillFile);

        var sessionId = session1.SessionId;
        await SuspendAndUntrackSessionForResumeAsync(session1);

        await using var session2 = await ResumeSessionAsync(sessionId, new ResumeSessionOptions
        { ContinuePendingWork = false, Skills = new SessionSkillOptions { Directories = [skillsDirectory] } });

        AssertSkill(
            await session2.Rpc.Skills.ListAsync(),
            skillName,
            "Scenario skill version two.",
            skillFile);
    }

    private static string WriteSkill(
        string skillsDirectory,
        string skillName,
        string description,
        string body)
    {
        var skillDirectory = Path.Join(skillsDirectory, skillName);
        Directory.CreateDirectory(skillDirectory);
        var skillFile = Path.Join(skillDirectory, "SKILL.md");
        File.WriteAllText(skillFile, CreateSkillContent(skillName, description, body));
        return skillFile;
    }

    private static string CreateSkillContent(string skillName, string description, string body) =>
        $"""
        ---
        name: {skillName}
        description: {description}
        ---

        # Scenario Reloadable Skill

        {body}
        """.ReplaceLineEndings("\n");

    private static void AssertSkill(
        SkillList list,
        string skillName,
        string description,
        string expectedPath)
    {
        var skill = Assert.Single(
            list.Skills,
            skill => string.Equals(skill.Name, skillName, StringComparison.Ordinal));
        Assert.True(skill.Enabled);
        Assert.Equal(description, skill.Description);
        Assert.Equal(expectedPath, skill.Path);
    }
}
