package e2e

import (
	"strings"
	"testing"
	"time"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
)

func TestAbortE2E(t *testing.T) {
	t.Parallel()
	ctx := testharness.NewTestContext(t)
	client := ctx.NewClient()
	t.Cleanup(func() { client.ForceStop() })

	// Verifies that Abort cleanly interrupts an active turn during tool execution.
	t.Run("should abort during active tool execution", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		type ValueParams struct {
			Value string `json:"value" jsonschema:"Value to analyze"`
		}
		toolStarted := make(chan string, 1)
		releaseTool := make(chan string, 1)

		slowTool := copilot.DefineTool("slow_analysis", "A slow analysis tool that blocks until released",
			func(params ValueParams, inv copilot.ToolInvocation) (string, error) {
				select {
				case toolStarted <- params.Value:
				default:
				}
				return <-releaseTool, nil
			})
		slowTool.SkipPermission = true

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Tools: copilot.SessionToolOptions{Definitions: []copilot.Tool{slowTool}}})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}
		t.Cleanup(func() { _ = session.Disconnect() })

		release := func() {
			select {
			case releaseTool <- "RELEASED_AFTER_ABORT":
			default:
			}
		}
		// Release the tool even if an assertion fails so its goroutine doesn't leak.
		t.Cleanup(release)

		sessionIdle := make(chan struct{}, 1)
		cancellationNotice := make(chan string, 1)
		session.On(func(event copilot.SessionEvent) {
			switch data := event.Data.(type) {
			case *copilot.SessionIdleData:
				select {
				case sessionIdle <- struct{}{}:
				default:
				}
			case *copilot.SessionInfoData:
				if data.InfoType == "cancellation" {
					select {
					case cancellationNotice <- data.Message:
					default:
					}
				}
			}
		})

		// Fire-and-forget
		go func() {
			_, _ = session.Send(t.Context(), copilot.MessageOptions{
				Prompt: "Use slow_analysis with value 'test_abort'. Wait for the result.",
			})
		}()

		// Wait for the tool to start executing
		var toolValue string
		select {
		case toolValue = <-toolStarted:
		case <-time.After(60 * time.Second):
			t.Fatal("Timed out waiting for slow_analysis tool to start")
		}
		if toolValue != "test_abort" {
			t.Errorf("Expected tool value 'test_abort', got %q", toolValue)
		}

		// Abort while the tool is running
		if err := session.Abort(t.Context()); err != nil {
			t.Fatalf("Abort failed: %v", err)
		}

		// Observe cancellation while the tool is still held, then release it.
		select {
		case notice := <-cancellationNotice:
			if notice != "Operation cancelled by user" {
				t.Errorf("Expected cancellation notice 'Operation cancelled by user', got %q", notice)
			}
		case <-time.After(10 * time.Second):
			t.Fatal("Timed out waiting for cancellation notice while tool was held")
		}
		release()
		select {
		case <-sessionIdle:
		case <-time.After(60 * time.Second):
			t.Fatal("Timed out waiting for session to become idle after tool abort")
		}

		// Session should be usable after abort.
		msg, err := session.SendAndWait(t.Context(), copilot.MessageOptions{
			Prompt: "Say 'tool_abort_recovery_ok'.",
		})
		if err != nil {
			t.Fatalf("Recovery SendAndWait failed after tool abort: %v", err)
		}
		if content := assistantContent(t, msg); !strings.Contains(content, "tool_abort_recovery_ok") {
			t.Errorf("Expected recovery message to contain 'tool_abort_recovery_ok', got %q", content)
		}
	})
}
