/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Microsoft.Copilot.Test.Harness;
using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

public class HooksE2ETests(E2ETestFixture fixture, ITestOutputHelper output) : E2ETestBase(fixture, "hooks", output)
{
    [Fact]
    public async Task Should_Invoke_Both_PreToolUse_And_PostToolUse_Hooks_For_Single_Tool_Call()
    {
        var preToolUseInputs = new List<PreToolUseHookInput>();
        var postToolUseInputs = new List<PostToolUseHookInput>();
        var invocationSessionIds = new List<string>();

        var session = await CreateSessionAsync(new CreateSessionOptions
        {
            Permissions = new SessionPermissionOptions
            {
                OnRequest = PermissionHandler.ApproveAll
            },
            Hooks = new SessionHookOptions
            {
                Handlers = new SessionHooks
                {
                    OnPreToolUse = (input, invocation) =>
                    {
                        preToolUseInputs.Add(input);
                        invocationSessionIds.Add(invocation.SessionId);
                        return Task.FromResult<PreToolUseHookOutput?>(new PreToolUseHookOutput { PermissionDecision = "allow" });
                    },
                    OnPostToolUse = (input, invocation) =>
                    {
                        postToolUseInputs.Add(input);
                        invocationSessionIds.Add(invocation.SessionId);
                        return Task.FromResult<PostToolUseHookOutput?>(null);
                    }
                }
            }
        });

        await File.WriteAllTextAsync(Path.Join(Ctx.WorkDir, "both.txt"), "Testing both hooks!");

        await TestHelper.SendAndGetFinalAssistantMessageAsync(session, new MessageOptions
        {
            Prompt = "Read the contents of both.txt"
        });

        // Both hooks should have been called
        Assert.NotEmpty(preToolUseInputs);
        Assert.NotEmpty(postToolUseInputs);
        Assert.All(invocationSessionIds, id => Assert.Equal(session.SessionId, id));
        Assert.Contains(postToolUseInputs, i => i.ToolResult != null);
        Assert.Contains(postToolUseInputs, i => !string.IsNullOrEmpty(i.ToolName));

        Assert.All(preToolUseInputs, input => Assert.False(string.IsNullOrEmpty(input.ToolCallId)));
        Assert.All(postToolUseInputs, post =>
        {
            Assert.False(string.IsNullOrEmpty(post.ToolCallId));
            var pre = Assert.Single(preToolUseInputs, input =>
                input.SessionId == post.SessionId && input.ToolCallId == post.ToolCallId);
            Assert.Equal(pre.ToolName, post.ToolName);
        });
    }

    [Fact]
    public async Task Should_Deny_Tool_Execution_When_PreToolUse_Returns_Deny()
    {
        var preToolUseInputs = new List<PreToolUseHookInput>();

        var session = await CreateSessionAsync(new CreateSessionOptions
        {
            Permissions = new SessionPermissionOptions
            {
                OnRequest = PermissionHandler.ApproveAll
            },
            Hooks = new SessionHookOptions
            {
                Handlers = new SessionHooks
                {
                    OnPreToolUse = (input, invocation) =>
                    {
                        preToolUseInputs.Add(input);
                        // Deny all tool calls
                        return Task.FromResult<PreToolUseHookOutput?>(new PreToolUseHookOutput { PermissionDecision = "deny" });
                    }
                }
            }
        });

        // Create a file
        var originalContent = "Original content that should not be modified";
        await File.WriteAllTextAsync(Path.Join(Ctx.WorkDir, "protected.txt"), originalContent);

        var response = await TestHelper.SendAndGetFinalAssistantMessageAsync(session, new MessageOptions
        {
            Prompt = "Edit protected.txt and replace 'Original' with 'Modified'"
        });

        // The hook should have been called
        Assert.NotEmpty(preToolUseInputs);

        // The response should be defined
        Assert.NotNull(response);

        // Strengthen: verify the actual deny behavior — the protected file was NOT
        // modified by the runtime even though the LLM tried to edit it. The pre-tool-use
        // hook denial blocks tool execution before it can mutate state.
        var actualContent = await File.ReadAllTextAsync(Path.Join(Ctx.WorkDir, "protected.txt"));
        Assert.Equal(originalContent, actualContent);
    }
}
