package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func run() (err error) {
	root := flag.String("root", "", "existing private directory for this example")
	flag.Parse()
	if *root == "" {
		flag.Usage()
		return nil
	}
	previousKeytar, hadKeytar := os.LookupEnv("COPILOT_DISABLE_KEYTAR")
	if err := os.Setenv("COPILOT_DISABLE_KEYTAR", "1"); err != nil {
		return err
	}
	defer func() {
		if hadKeytar {
			err = errors.Join(err, os.Setenv("COPILOT_DISABLE_KEYTAR", previousKeytar))
		} else {
			err = errors.Join(err, os.Unsetenv("COPILOT_DISABLE_KEYTAR"))
		}
	}()
	directory, err := os.MkdirTemp(*root, "mcp-config-")
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, os.RemoveAll(directory)) }()
	configDir := filepath.Join(directory, "selected-config")
	if err := os.Mkdir(configDir, 0o700); err != nil {
		return err
	}
	client := copilot.NewClient(&copilot.ClientOptions{
		Mode:    copilot.ModeCodingAgent,
		Runtime: copilot.InProcessConnection{BaseDirectory: configDir},
	})
	ctx := context.Background()
	if err := client.Start(ctx); err != nil {
		return err
	}
	defer func() { err = errors.Join(err, client.Stop()) }()
	api := client.RPC.MCP.Config()
	if _, err := api.AddWithOptions(ctx, &rpc.MCPConfigAddOptions{
		Config:    &rpc.MCPServerConfigStdio{Command: "example-server-not-started", Args: []string{}, Tools: []string{"*"}},
		ConfigDir: &configDir,
		Name:      "saved-server",
	}); err != nil {
		return err
	}
	for _, removed := range []bool{false, true} {
		if removed {
			if _, err := api.RemoveWithOptions(ctx, &rpc.MCPConfigRemoveOptions{Name: "saved-server", ConfigDir: &configDir}); err != nil {
				return err
			}
		}
		snapshot, err := api.List(ctx, nil)
		if err != nil {
			return err
		}
		_, configured := snapshot.Servers["saved-server"]
		if configured == removed {
			return fmt.Errorf("server configured = %t after removal = %t", configured, removed)
		}
		fmt.Printf("selected root: server configured = %t\n", configured)
	}
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
