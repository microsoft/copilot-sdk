package main

import (
	"context"
	"errors"
	"fmt"
	"os"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func openSession(ctx context.Context, directory, sessionID string) (id string, err error) {
	client := copilot.NewClient(&copilot.ClientOptions{
		Mode:    copilot.ModeCodingAgent,
		Runtime: copilot.ForDefault(&copilot.OwnedRuntimeOptions{BaseDirectory: directory}),
		GitHub:  copilot.ClientGitHubOptions{UseLoggedInUser: copilot.Bool(false)},
	})
	defer func() { err = errors.Join(err, client.Stop()) }()

	var session *copilot.Session
	if sessionID == "" {
		session, err = client.CreateSessionWithLifecycleMode(ctx, &copilot.CreateSessionOptions{
			Name:                  "sdk-lifecycle-example",
			WorkingDirectory:      directory,
			ConfigDirectory:       directory,
			EnableConfigDiscovery: copilot.Bool(false),
		}, copilot.SessionLifecycleModePerTurn)
	} else {
		if err := client.Start(ctx); err != nil {
			return "", err
		}
		mode := copilot.SessionLifecycleModePerTurn
		opened, openErr := client.RPC.Sessions.OpenWithOptions(ctx, rpc.SessionsOpenResume{
			SessionID: sessionID,
			Options: &rpc.SessionOpenOptions{
				WorkingDirectory:      &directory,
				ConfigDirectory:       &directory,
				EnableConfigDiscovery: copilot.Bool(false),
			},
		}, &rpc.SessionOpenRequestOptions{SessionLifecycleMode: &mode})
		if openErr != nil {
			return "", openErr
		}
		if opened.SessionID == nil || *opened.SessionID != sessionID {
			return "", fmt.Errorf("sessions.open did not resume %q: %#v", sessionID, opened)
		}
		fmt.Println("sessions.open cold-resumed the session with compatible request options.")
		session, err = client.ResumeSessionWithLifecycleMode(ctx, sessionID, &copilot.ResumeSessionOptions{
			WorkingDirectory:      directory,
			ConfigDirectory:       directory,
			EnableConfigDiscovery: copilot.Bool(false),
		}, copilot.SessionLifecycleModePerTurn)
	}
	if err != nil {
		return "", err
	}
	return session.SessionID, nil
}

func run() (err error) {
	directory, err := os.MkdirTemp("", "copilot-session-lifecycle-")
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, os.RemoveAll(directory)) }()

	ctx := context.Background()
	sessionID, err := openSession(ctx, directory, "")
	if err != nil {
		return fmt.Errorf("create session and stop its host: %w", err)
	}
	fmt.Println("Created a named session through the SDK; its host stopped.")

	resumedID, err := openSession(ctx, directory, sessionID)
	if err != nil {
		return fmt.Errorf("cold-resume session and stop its host: %w", err)
	}
	if resumedID != sessionID {
		return fmt.Errorf("cold resume returned %q, want %q", resumedID, sessionID)
	}
	fmt.Println("Cold resume preserved the session ID with per-turn lifecycle selection.")
	fmt.Println("Both SDK-owned runtime hosts stopped. No inference request was needed.")
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
