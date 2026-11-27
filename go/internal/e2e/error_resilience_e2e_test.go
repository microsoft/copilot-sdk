package e2e

import (
	"context"
	"testing"
	"time"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
)

func TestErrorResilienceE2E(t *testing.T) {
	t.Parallel()
	ctx := testharness.NewTestContext(t)
	client := ctx.NewClient()
	t.Cleanup(func() { client.ForceStop() })

	t.Run("should throw when sending to disconnected session", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}
		if err := session.Disconnect(); err != nil {
			t.Fatalf("Disconnect failed: %v", err)
		}

		timeoutCtx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
		defer cancel()
		if _, err := session.SendAndWait(timeoutCtx, copilot.MessageOptions{Prompt: "Hello"}); err == nil {
			t.Fatal("Expected SendAndWait on disconnected session to fail")
		}
	})
}
