package e2e

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

type providedSkill struct {
	descriptor rpc.SkillProviderDescriptor
	read       func() (string, error)
}

type testSkillProvider struct {
	mu     sync.Mutex
	skills []providedSkill
	calls  []string
}

func newTestSkillProvider(skills []providedSkill) *testSkillProvider {
	return &testSkillProvider{skills: skills}
}

func providedSkillWithMarkdown(name, description, markdown string) providedSkill {
	return providedSkill{
		descriptor: rpc.SkillProviderDescriptor{Name: name, Description: description},
		read:       func() (string, error) { return markdown, nil },
	}
}

func (p *testSkillProvider) ListSkills(ctx context.Context) ([]rpc.SkillProviderDescriptor, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls = append(p.calls, "list")
	skills := make([]rpc.SkillProviderDescriptor, 0, len(p.skills))
	for _, skill := range p.skills {
		skills = append(skills, skill.descriptor)
	}
	return skills, nil
}

func (p *testSkillProvider) ReadSkill(ctx context.Context, name string) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls = append(p.calls, "read:"+name)
	for _, skill := range p.skills {
		if skill.descriptor.Name == name {
			return skill.read()
		}
	}
	return "", copilot.ErrSkillNotFound
}

func (p *testSkillProvider) Calls() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	calls := make([]string, len(p.calls))
	copy(calls, p.calls)
	return calls
}

func (p *testSkillProvider) Reads() []string {
	calls := p.Calls()
	reads := make([]string, 0, len(calls))
	for _, call := range calls {
		if strings.HasPrefix(call, "read:") {
			reads = append(reads, strings.TrimPrefix(call, "read:"))
		}
	}
	return reads
}

// blockingSkillProvider blocks ListSkills until its context is cancelled.
type blockingSkillProvider struct {
	entered    chan struct{}
	cancelled  chan struct{}
	enterOnce  sync.Once
	cancelOnce sync.Once
}

func newBlockingSkillProvider() *blockingSkillProvider {
	return &blockingSkillProvider{entered: make(chan struct{}), cancelled: make(chan struct{})}
}

func (p *blockingSkillProvider) ListSkills(ctx context.Context) ([]rpc.SkillProviderDescriptor, error) {
	p.enterOnce.Do(func() { close(p.entered) })
	<-ctx.Done()
	p.cancelOnce.Do(func() { close(p.cancelled) })
	return nil, ctx.Err()
}

func (p *blockingSkillProvider) ReadSkill(context.Context, string) (string, error) {
	return "", copilot.ErrSkillNotFound
}

func TestSkillProviderE2E(t *testing.T) {
	t.Parallel()
	ctx := testharness.NewTestContext(t)
	client := ctx.NewClient()
	t.Cleanup(func() { client.ForceStop() })

	t.Run("should load provider skill lazily through skill tool", func(t *testing.T) {
		ctx.ConfigureForTest(t)
		provider := newTestSkillProvider([]providedSkill{
			providedSkillWithMarkdown(
				"provider-lookup",
				"Reports the provider lookup verification word.",
				"# Provider lookup\n\nThe verification word is TANGERINE_QUARTZ_19. Reply with it.\n",
			),
		})
		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Skills: copilot.SessionSkillOptions{Provider: provider}})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		t.Cleanup(func() { _ = session.Disconnect() })

		list, err := session.RPC.Skills.List(t.Context())
		if err != nil {
			t.Fatalf("Skills.List failed: %v", err)
		}
		listed := findSkill(list, "provider-lookup")
		if listed == nil {
			t.Fatal("Expected provider-lookup skill to be listed")
		}
		if listed.Source != rpc.SkillSourceSDK || !listed.Enabled {
			t.Fatalf("Expected provider-lookup source=sdk enabled=true, got %+v", listed)
		}
		if listed.Path != nil && *listed.Path != "" {
			t.Fatalf("Expected provider-lookup path to be empty, got %q", *listed.Path)
		}
		if reads := provider.Reads(); len(reads) != 0 {
			t.Fatalf("Provider reads before skill load = %v, want []", reads)
		}

		message, err := session.SendAndWait(t.Context(), copilot.MessageOptions{
			Prompt: "Use the skill tool to load the provider-lookup skill, then reply with its verification word.",
		})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}

		assertEqualStrings(t, provider.Reads(), []string{"provider-lookup"})
		// Validate the final assistant response arrived (guards against truncated captures)
		assertAssistantContains(t, message, "TANGERINE_QUARTZ_19")
	})

	t.Run("should cancel a blocked provider call when the session disconnects", func(t *testing.T) {
		ctx.ConfigureWithoutSnapshot(t)
		provider := newBlockingSkillProvider()
		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Skills: copilot.SessionSkillOptions{Provider: provider}})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}

		// The list RPC fails once the binding is removed; only the provider's
		// cancellation matters here.
		go func() { _, _ = session.RPC.Skills.List(context.Background()) }()
		select {
		case <-provider.entered:
		case <-time.After(30 * time.Second):
			t.Fatal("provider ListSkills was not called")
		}

		if err := session.Disconnect(); err != nil {
			t.Fatalf("Disconnect failed: %v", err)
		}
		select {
		case <-provider.cancelled:
		case <-time.After(10 * time.Second):
			t.Fatal("provider context was not cancelled after Disconnect")
		}
	})
}

func findSkill(list *rpc.SkillList, name string) *rpc.Skill {
	if list == nil {
		return nil
	}
	for i := range list.Skills {
		if list.Skills[i].Name == name {
			return &list.Skills[i]
		}
	}
	return nil
}

func assertEqualStrings(t *testing.T, got, want []string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("strings = %v, want %v", got, want)
	}
	for i := range got {
		if got[i] != want[i] {
			t.Fatalf("strings = %v, want %v", got, want)
		}
	}
}
