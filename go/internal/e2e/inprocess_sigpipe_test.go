//go:build unix

package e2e

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func TestInProcessSurvivesClosedMCPInput(t *testing.T) {
	if !testharness.IsInProcessTransport() {
		t.Skip("in-process SIGPIPE regression runs only under the inprocess transport cell")
	}

	const child = "COPILOT_SIGPIPE_TEST_CONFIG"
	var config struct {
		HomeDir     string
		WorkDir     string
		Token       string
		TestOptions *copilot.InternalTestOptions
	}
	configPath := os.Getenv(child)
	if configPath == "" {
		// The parent owns the proxy but must not start a native host.
		fixture := testharness.NewTestContext(t)
		var options *copilot.ClientOptions
		unstarted := fixture.NewClient(func(value *copilot.ClientOptions) {
			value.Runtime = copilot.InProcessConnection{}
			options = value
		})
		t.Cleanup(func() { unstarted.ForceStop() })
		config.HomeDir = fixture.HomeDir
		config.WorkDir = fixture.WorkDir
		config.Token = options.GitHub.Token
		config.TestOptions = options.InternalTestOptions
		data, err := json.Marshal(config)
		if err != nil {
			t.Fatal(err)
		}
		configPath = filepath.Join(fixture.WorkDir, "host-config.json")
		if err := os.WriteFile(configPath, data, 0600); err != nil {
			t.Fatal(err)
		}
		executable, err := os.Executable()
		if err != nil {
			t.Fatal(err)
		}
		command := exec.CommandContext(t.Context(), executable, "-test.run=^TestInProcessSurvivesClosedMCPInput$", "-test.v")
		command.Env = append(fixture.Env(), child+"="+configPath)
		command.Dir = fixture.WorkDir
		if output, err := command.CombinedOutput(); err != nil {
			t.Fatalf("in-process host failed: %v\n%s", err, output)
		}
		return
	}

	data, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &config); err != nil {
		t.Fatal(err)
	}
	options := &copilot.ClientOptions{
		Mode:                copilot.ModeCodingAgent,
		Runtime:             copilot.InProcessConnection{BaseDirectory: config.HomeDir},
		InternalTestOptions: config.TestOptions,
	}
	options.GitHub.Token = config.Token
	client := copilot.NewClient(options)
	t.Cleanup(func() { client.ForceStop() })
	script := filepath.Join(config.WorkDir, "closed-input.mjs")
	marker := filepath.Join(config.WorkDir, "closed-input.marker")
	if err := os.WriteFile(script, []byte(`
import { closeSync, openSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
const input = createInterface({ input: process.stdin });
const parent = process.ppid;
input.once("line", (line) => {
    const request = JSON.parse(line);
    input.close();
    process.stdin.pause();
    closeSync(0);
    // Close the MCP pipe, but keep fd 0 occupied for libuv's internal probes.
    if (openSync("/dev/null", "r") !== 0) {
        throw new Error("expected /dev/null to occupy closed stdin");
    }
    writeFileSync(process.argv[2], "stdin closed\n");
    process.stdout.write(JSON.stringify({
        jsonrpc: "2.0",
        id: request.id,
        result: {
            protocolVersion: request.params.protocolVersion,
            capabilities: { tools: {} },
            serverInfo: { name: "closed-input", version: "1.0.0" }
        }
    }) + "\n");
});
setInterval(() => {
    if (process.ppid !== parent) process.exit(0);
}, 1000);
`), 0600); err != nil {
		t.Fatal(err)
	}
	session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{
		Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll},
		MCP: copilot.SessionMCPOptions{Servers: map[string]copilot.MCPServerConfig{
			"closed-input": copilot.MCPStdioServerConfig{
				Command: "node", Args: []string{script, marker}, Tools: []string{"*"},
				WorkingDirectory: config.WorkDir,
			},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	waitForMCPServerStatus(t, session, "closed-input", rpc.MCPServerStatusFailed)
	if data, err := os.ReadFile(marker); err != nil || string(data) != "stdin closed\n" {
		t.Fatalf("MCP fixture did not close stdin: marker=%q, error=%v", data, err)
	}
	result, err := session.RPC.MCP.List(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	var failure string
	for _, server := range result.Servers {
		if server.Name == "closed-input" && server.Error != nil {
			failure = *server.Error
			break
		}
	}
	if !strings.Contains(strings.ToLower(failure), "broken pipe") {
		t.Fatalf("expected a closed-pipe MCP error, got %q", failure)
	}
	if _, err := client.Ping(t.Context(), "survived closed MCP input"); err != nil {
		t.Fatal(err)
	}
}
