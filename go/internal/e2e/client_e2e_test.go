package e2e

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"testing"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func TestStopDetachesBothRealSessions(t *testing.T) {
	t.Parallel()
	cliPath := testharness.CLIPath()
	if cliPath == "" {
		t.Fatal("Copilot runtime not found")
	}
	node, err := exec.LookPath("node")
	if err != nil {
		t.Fatal(err)
	}
	home, workDir := t.TempDir(), t.TempDir()
	witness := filepath.Join(workDir, "detached.json")
	connection := copilot.SpawnedStdioConnection{
		Path:          node,
		Args:          []string{testharness.RepoPath("test", "harness", "stdio-detach-gate.cjs"), cliPath, witness},
		BaseDirectory: home, WorkingDirectory: workDir,
	}
	newClient := func(connection copilot.SpawnedStdioConnection) *copilot.Client {
		client := copilot.NewClient(&copilot.ClientOptions{
			Mode:    copilot.ModeCodingAgent,
			Runtime: connection,
			GitHub:  copilot.ClientGitHubOptions{UseLoggedInUser: copilot.Bool(false)},
		})
		t.Cleanup(client.ForceStop)
		return client
	}
	client := newClient(connection)
	var sessionIDs []string
	permissions := copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}
	for range 2 {
		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{
			WorkingDirectory: workDir, EnableConfigDiscovery: copilot.Bool(false), Permissions: permissions,
		})
		if err != nil {
			t.Fatal(err)
		}
		sessionIDs = append(sessionIDs, session.SessionID)
		_, err = session.RPC.History.ImportConversation(t.Context(), &rpc.SessionHistoryImportConversationRequest{
			Events: []rpc.HistoryImportConversationEvent{
				rpc.HistoryImportConversationEventUserMessage{Data: rpc.HistoryImportUserMessage{Content: session.SessionID}},
			},
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	if err := client.Stop(); err != nil {
		t.Fatalf("Stop with both real detach replies gated: %v", err)
	}
	data, err := os.ReadFile(witness)
	if err != nil {
		t.Fatal(err)
	}
	var detached []string
	if err := json.Unmarshal(data, &detached); err != nil {
		t.Fatal(err)
	}
	slices.Sort(detached)
	slices.Sort(sessionIDs)
	if !slices.Equal(detached, sessionIDs) {
		t.Fatalf("Detached sessions %v, want %v", detached, sessionIDs)
	}

	connection.Path, connection.Args = cliPath, nil
	resumedClient := newClient(connection)
	for _, id := range sessionIDs {
		session, err := resumedClient.ResumeSession(t.Context(), id, &copilot.ResumeSessionOptions{
			WorkingDirectory: workDir, EnableConfigDiscovery: copilot.Bool(false), Permissions: permissions,
		})
		if err != nil {
			t.Fatalf("Cold resume %s: %v", id, err)
		}
		events, err := session.GetEvents(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		var messages []string
		for _, event := range events {
			if message, ok := event.Data.(*copilot.UserMessageData); ok {
				messages = append(messages, message.Content)
			}
		}
		if !slices.Equal(messages, []string{id}) {
			t.Fatalf("Resumed session %s has user messages %q, want [%q]", id, messages, id)
		}
	}
	if err := resumedClient.Stop(); err != nil {
		t.Fatalf("Stop after resuming: %v", err)
	}
}

func TestClientStdioOverrideWithCustomEnv(t *testing.T) {
	t.Setenv("COPILOT_SDK_DEFAULT_CONNECTION", "stdio")
	cliPath := testharness.CLIPath()
	if cliPath == "" {
		t.Fatal("CLI not found. Run 'npm install' in the nodejs directory first.")
	}

	connection := copilot.ForDefault(&copilot.OwnedRuntimeOptions{BaseDirectory: t.TempDir()})
	stdio, ok := connection.(copilot.SpawnedStdioConnection)
	if !ok {
		t.Fatalf("Expected stdio override, got %T", connection)
	}
	stdio.Env = []string{"COPILOT_CLI_PATH=" + cliPath}
	client := copilot.NewClient(&copilot.ClientOptions{
		Mode:    copilot.ModeCodingAgent,
		Runtime: stdio,
	})
	t.Cleanup(func() { client.ForceStop() })

	if err := client.Start(t.Context()); err != nil {
		t.Fatalf("Failed to start client: %v", err)
	}

	pong, err := client.Ping(t.Context(), "custom environment")
	if err != nil {
		t.Fatalf("Failed to ping: %v", err)
	}
	if pong.Message != "pong: custom environment" {
		t.Errorf("Expected subprocess response, got %q", pong.Message)
	}
	status, err := client.GetStatus(t.Context())
	if err != nil {
		t.Fatalf("Failed to get status: %v", err)
	}
	if status.Version == "" {
		t.Error("Expected status.Version to be non-empty")
	}
	if status.ProtocolVersion < 1 {
		t.Errorf("Expected status.ProtocolVersion >= 1, got %d", status.ProtocolVersion)
	}

	if err := client.Stop(); err != nil {
		t.Errorf("Expected no errors on stop, got %v", err)
	}
}

func TestClientE2E(t *testing.T) {
	t.Parallel()
	cliPath := testharness.CLIPath()
	if cliPath == "" {
		t.Fatal("CLI not found. Run 'npm install' in the nodejs directory first.")
	}

	t.Run("should start and connect to server using tcp", func(t *testing.T) {
		for _, tc := range []struct {
			name string
			args []string
		}{
			{name: "default"},
			{name: "ipv6", args: []string{"--host", "::1"}},
			{name: "wildcard-ipv4", args: []string{"--host", "0.0.0.0"}},
			{name: "wildcard-ipv6", args: []string{"--host=::"}},
		} {
			t.Run(tc.name, func(t *testing.T) {
				client := copilot.NewClient(&copilot.ClientOptions{Mode: copilot.ModeCodingAgent, Runtime: copilot.SpawnedTCPConnection{Path: cliPath, Args: tc.args}})
				t.Cleanup(func() { client.ForceStop() })

				if err := client.Start(t.Context()); err != nil {
					t.Fatalf("Failed to start client: %v", err)
				}

				pong, err := client.Ping(t.Context(), "test message")
				if err != nil {
					t.Fatalf("Failed to ping: %v", err)
				}

				if pong.Message != "pong: test message" {
					t.Errorf("Expected pong.message to be 'pong: test message', got %q", pong.Message)
				}

				if pong.Timestamp.IsZero() {
					t.Errorf("Expected non-zero pong.timestamp, got %s", pong.Timestamp)
				}

				if err := client.Stop(); err != nil {
					t.Errorf("Expected no errors on stop, got %v", err)
				}
			})
		}
	})

	t.Run("should use configured github host for authentication", func(t *testing.T) {
		for _, testCase := range []struct {
			name, copilotHost, ghHost, expectedHost string
		}{
			{"precedence", "tenant.ghe.example", "fallback.ghe.example", "https://tenant.ghe.example"},
			{"fallback", "", "fallback.ghe.example", "https://fallback.ghe.example"},
			{"default", "", "", "https://github.com"},
		} {
			t.Run(testCase.name, func(t *testing.T) {
				ctx := testharness.NewTestContext(t)
				ctx.ConfigureWithoutSnapshot(t)
				client := ctx.NewClient(func(options *copilot.ClientOptions) {
					// This exercises process-environment precedence, not embedded configuration.
					options.Runtime = copilot.SpawnedStdioConnection{
						Path: ctx.CLIPath,
						Env: append(ctx.Env(),
							"COPILOT_GH_HOST="+testCase.copilotHost,
							"GH_HOST="+testCase.ghHost,
						),
					}
				})
				t.Cleanup(func() { client.ForceStop() })

				if err := client.Start(t.Context()); err != nil {
					t.Fatalf("Failed to start client: %v", err)
				}
				status, err := client.GetAuthStatus(t.Context())
				if err != nil {
					t.Fatalf("Failed to get auth status: %v", err)
				}
				if !status.IsAuthenticated {
					t.Fatalf("Expected authenticated status, got %+v", status)
				}
				if status.Host == nil || *status.Host != testCase.expectedHost {
					t.Errorf("Expected %s auth host, got %v", testCase.expectedHost, status.Host)
				}
			})
		}
	})

	t.Run("should list models when authenticated", func(t *testing.T) {
		ctx := testharness.NewTestContext(t)
		ctx.ConfigureWithoutSnapshot(t)
		client := ctx.NewClient()
		t.Cleanup(func() { client.ForceStop() })

		if err := client.Start(t.Context()); err != nil {
			t.Fatalf("Failed to start client: %v", err)
		}

		authStatus, err := client.GetAuthStatus(t.Context())
		if err != nil {
			t.Fatalf("Failed to get auth status: %v", err)
		}

		if !authStatus.IsAuthenticated {
			t.Fatalf("Expected authenticated status, got %+v", authStatus)
		}

		models, err := client.ListModels(t.Context())
		if err != nil {
			t.Fatalf("Failed to list models: %v", err)
		}

		if len(models) == 0 {
			t.Fatal("Expected at least one model from the replay proxy")
		}
		model := models[0]
		if model.ID == "" {
			t.Error("Expected model.ID to be non-empty")
		}
		if model.Name == "" {
			t.Error("Expected model.Name to be non-empty")
		}

		if err := client.Stop(); err != nil {
			t.Errorf("Expected no errors on stop, got %v", err)
		}
	})

	t.Run("should report error when CLI fails to start", func(t *testing.T) {
		client := copilot.NewClient(&copilot.ClientOptions{Mode: copilot.ModeCodingAgent, Runtime: copilot.SpawnedStdioConnection{
			Path: cliPath,
			Args: []string{"--nonexistent-flag-for-testing"},
		},
		})
		t.Cleanup(func() { client.ForceStop() })

		err := client.Start(t.Context())
		if err == nil {
			t.Fatal("Expected Start to fail with invalid CLI args")
		}

		// Verify subsequent calls also fail (don't hang)
		session, err := client.CreateSession(t.Context(), nil)
		if err == nil {
			_, err = session.Send(t.Context(), copilot.MessageOptions{Prompt: "test"})
		}
		if err == nil {
			t.Fatal("Expected CreateSession/Send to fail after CLI exit")
		}
	})
}
