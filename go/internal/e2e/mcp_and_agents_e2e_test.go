package e2e

import (
	"path/filepath"
	"strings"
	"testing"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func TestMCPServersE2E(t *testing.T) {
	t.Parallel()
	ctx := testharness.NewTestContext(t)
	client := ctx.NewClient()
	t.Cleanup(func() { client.ForceStop() })

	t.Run("accept MCP server config on resume", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		// Create a session first
		session1, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}
		sessionID := session1.SessionID

		// Resume with MCP servers
		mcpServers := testMCPServers(t, "test-server")

		session2, err := client.ResumeSession(t.Context(), sessionID, &copilot.ResumeSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, MCP: copilot.SessionMCPOptions{Servers: mcpServers}})
		if err != nil {
			t.Fatalf("Failed to resume session: %v", err)
		}

		if session2.SessionID != sessionID {
			t.Errorf("Expected session ID %s, got %s", sessionID, session2.SessionID)
		}
		waitForMCPServerStatus(t, session2, "test-server", rpc.MCPServerStatusConnected)

		session2.Disconnect()
	})

	t.Run("should pass literal env values to MCP server subprocess", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		mcpServerPath := testharness.RepoPath("test", "harness", "test-mcp-server.mjs")
		mcpServerDir := filepath.Dir(mcpServerPath)

		mcpServers := map[string]copilot.MCPServerConfig{
			"env-echo": copilot.MCPStdioServerConfig{
				Command:          "node",
				Args:             []string{mcpServerPath},
				Tools:            []string{"*"},
				Env:              map[string]string{"TEST_SECRET": "hunter2"},
				WorkingDirectory: mcpServerDir,
			},
		}

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{MCP: copilot.SessionMCPOptions{Servers: mcpServers}, Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}

		if session.SessionID == "" {
			t.Error("Expected non-empty session ID")
		}
		waitForMCPServerStatus(t, session, "env-echo", rpc.MCPServerStatusConnected)

		message, err := session.SendAndWait(t.Context(), copilot.MessageOptions{
			Prompt: "Use the env-echo/get_env tool to read the TEST_SECRET environment variable. Reply with just the value, nothing else.",
		})
		if err != nil {
			t.Fatalf("Failed to send message: %v", err)
		}

		if md, ok := message.Data.(*copilot.AssistantMessageData); !ok || !strings.Contains(md.Content, "hunter2") {
			t.Errorf("Expected message to contain 'hunter2', got: %v", message.Data)
		}

		session.Disconnect()
	})
}
