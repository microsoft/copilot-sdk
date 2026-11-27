package e2e

import (
	"sync"
	"testing"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
)

func TestStreamingFidelityE2E(t *testing.T) {
	t.Parallel()
	ctx := testharness.NewTestContext(t)
	client := ctx.NewClient()
	t.Cleanup(func() { client.ForceStop() })

	t.Run("should produce delta events when streaming is enabled", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Events: copilot.SessionEventOptions{Streaming: copilot.Bool(true)}})
		if err != nil {
			t.Fatalf("Failed to create session with streaming: %v", err)
		}

		var events []copilot.SessionEvent
		var mu sync.Mutex
		session.On(func(event copilot.SessionEvent) {
			mu.Lock()
			events = append(events, event)
			mu.Unlock()
		})

		_, err = session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "Count from 1 to 5, separated by commas."})
		if err != nil {
			t.Fatalf("Failed to send message: %v", err)
		}

		mu.Lock()
		snapshot := make([]copilot.SessionEvent, len(events))
		copy(snapshot, events)
		mu.Unlock()

		// Should have streaming deltas before the final message
		var deltaEvents []copilot.SessionEvent
		for _, e := range snapshot {
			if e.Type() == "assistant.message_delta" {
				deltaEvents = append(deltaEvents, e)
			}
		}
		if len(deltaEvents) < 1 {
			t.Error("Expected at least 1 delta event")
		}

		// Deltas should have content
		for _, delta := range deltaEvents {
			if dd, ok := delta.Data.(*copilot.AssistantMessageDeltaData); !ok || dd.DeltaContent == "" {
				t.Error("Expected delta to have content")
			}
		}

		// Should still have a final assistant.message
		hasAssistantMessage := false
		for _, e := range snapshot {
			if e.Type() == "assistant.message" {
				hasAssistantMessage = true
				break
			}
		}
		if !hasAssistantMessage {
			t.Error("Expected a final assistant.message event")
		}

		// Deltas should come before the final message
		firstDeltaIdx := -1
		lastAssistantIdx := -1
		for i, e := range snapshot {
			if e.Type() == "assistant.message_delta" && firstDeltaIdx == -1 {
				firstDeltaIdx = i
			}
			if e.Type() == "assistant.message" {
				lastAssistantIdx = i
			}
		}
		if firstDeltaIdx >= lastAssistantIdx {
			t.Errorf("Expected deltas before final message, got delta at %d, message at %d", firstDeltaIdx, lastAssistantIdx)
		}
	})

	t.Run("should not produce deltas when streaming is disabled", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Events: copilot.SessionEventOptions{Streaming: copilot.Bool(false)}})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}

		var events []copilot.SessionEvent
		var mu sync.Mutex
		session.On(func(event copilot.SessionEvent) {
			mu.Lock()
			events = append(events, event)
			mu.Unlock()
		})

		_, err = session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "Say 'hello world'."})
		if err != nil {
			t.Fatalf("Failed to send message: %v", err)
		}

		mu.Lock()
		snapshot := make([]copilot.SessionEvent, len(events))
		copy(snapshot, events)
		mu.Unlock()

		// No deltas when streaming is off
		var deltaEvents []copilot.SessionEvent
		for _, e := range snapshot {
			if e.Type() == "assistant.message_delta" {
				deltaEvents = append(deltaEvents, e)
			}
		}
		if len(deltaEvents) != 0 {
			t.Errorf("Expected no delta events, got %d", len(deltaEvents))
		}

		// But should still have a final assistant.message
		var assistantEvents []copilot.SessionEvent
		for _, e := range snapshot {
			if e.Type() == "assistant.message" {
				assistantEvents = append(assistantEvents, e)
			}
		}
		if len(assistantEvents) < 1 {
			t.Error("Expected at least 1 assistant.message event")
		}
	})
}
