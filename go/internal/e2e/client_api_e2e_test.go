package e2e

import (
	"strings"
	"testing"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
)

// Mirrors dotnet/test/ClientSessionManagementTests.cs (snapshot category "client_api").
func TestClientAPIE2E(t *testing.T) {
	t.Parallel()
	ctx := testharness.NewTestContext(t)
	client := ctx.NewClient()
	t.Cleanup(func() { client.ForceStop() })

	if err := client.Start(t.Context()); err != nil {
		t.Fatalf("Failed to start client: %v", err)
	}

	t.Run("should report error when deleting unknown session id", func(t *testing.T) {
		sessionID := "00000000-0000-0000-0000-000000000000"
		err := client.DeleteSession(t.Context(), sessionID)
		if err == nil {
			t.Fatal("Expected DeleteSession to fail for unknown id")
		}
		expectedMessage := "not found"
		if !strings.Contains(strings.ToLower(err.Error()), expectedMessage) {
			t.Errorf("Expected error mentioning %q, got %v", expectedMessage, err)
		}
	})

	t.Run("should get null last session id before any sessions exist", func(t *testing.T) {
		freshClient := ctx.NewClient(func(options *copilot.ClientOptions) {
			connection := options.Runtime.(copilot.SpawnedStdioConnection)
			connection.BaseDirectory = t.TempDir()
			options.Runtime = connection
		})
		t.Cleanup(func() { freshClient.ForceStop() })

		if err := freshClient.Start(t.Context()); err != nil {
			t.Fatalf("Failed to start fresh client: %v", err)
		}

		result, err := freshClient.GetLastSessionID(t.Context())
		if err != nil {
			t.Fatalf("Failed to get last session id: %v", err)
		}
		if result != nil {
			t.Errorf("Expected nil last session id on fresh client, got %q", *result)
		}
	})

	t.Run("should report error when setting foreground session in headless mode", func(t *testing.T) {
		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}
		t.Cleanup(func() { session.Disconnect() })

		err = client.SetForegroundSessionID(t.Context(), session.SessionID)
		if err == nil {
			t.Fatal("Expected SetForegroundSessionID to fail in headless mode")
		}
		if !strings.Contains(err.Error(), "Not running in TUI+server mode") {
			t.Errorf("Expected error mentioning 'Not running in TUI+server mode', got %v", err)
		}
		foreground, err := client.GetForegroundSessionID(t.Context())
		if err != nil {
			t.Fatalf("Failed to get foreground session id: %v", err)
		}
		if foreground != nil {
			t.Errorf("Expected nil foreground session id in headless mode, got %q", *foreground)
		}
	})
}
