// Copyright (c) Microsoft Corporation. All rights reserved.

package e2e

import (
	"context"
	"encoding/json"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func TestScenarioTestingSendsE2E(t *testing.T) {
	t.Parallel()
	t.Run("should send complete scenario message wire shape", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
		defer cancel()
		f := newGeneratedRPCFixture(t, ctx)

		var captured map[string]any
		f.server.SetRequestHandler("session.send", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
			if err := json.Unmarshal(params, &captured); err != nil {
				return nil, &jsonrpc2.Error{Code: -32000, Message: err.Error()}
			}
			return json.RawMessage(`{"messageId":"scenario-client-message"}`), nil
		})

		canvasID := "diff"
		instanceID := "diff-17"
		blobData := "QVBQX0JMT0I="
		blobName := "scenario-wire-blob.txt"
		messageID, err := f.session.Send(ctx, copilot.MessageOptions{
			Prompt:        "Use the hidden scenario context.",
			DisplayPrompt: "Review selected scenario context",
			Mode:          "enqueue",
			AgentMode:     copilot.AgentModeInteractive,
			Source:        copilot.MessageSourceAgent("scenario-client"),
			RequestHeaders: map[string]string{
				"x-scenario-request": "wire-shape",
			},
			Attachments: []copilot.Attachment{
				rpc.AttachmentFile{
					DisplayName: "scenario-wire-file.txt",
					Path:        `Q:\scenario-wire-file.txt`,
					LineRange:   &rpc.AttachmentFileLineRange{Start: 3, End: 9},
				},
				rpc.AttachmentDirectory{
					DisplayName: "scenario-wire-directory",
					Path:        `Q:\scenario-wire-directory`,
				},
				rpc.AttachmentSelection{
					DisplayName: "Program.cs",
					FilePath:    `Q:\Program.cs`,
					Text:        "SCENARIO_SELECTION",
					Selection: rpc.AttachmentSelectionDetails{
						Start: rpc.AttachmentSelectionDetailsStart{Line: 16, Character: 0},
						End:   rpc.AttachmentSelectionDetailsEnd{Line: 16, Character: 13},
					},
				},
				rpc.AttachmentGitHubReference{
					Number:        610,
					ReferenceType: rpc.AttachmentGitHubReferenceTypePr,
					State:         "open",
					Title:         "Scenario-shaped E2E coverage",
					URL:           "https://github.com/github/copilot-sdk/pull/610",
				},
				rpc.AttachmentBlob{
					Data:        &blobData,
					MIMEType:    "text/plain",
					DisplayName: &blobName,
				},
				rpc.AttachmentExtensionContext{
					CapturedAt:  time.Date(2026, 9, 17, 20, 0, 0, 0, time.UTC),
					ExtensionID: "scenario-client:code-review",
					CanvasID:    &canvasID,
					InstanceID:  &instanceID,
					Title:       "Selected change",
					Payload:     map[string]any{"selection": "SCENARIO_SELECTION", "line": float64(17)},
				},
			},
		})
		if err != nil {
			t.Fatal(err)
		}
		if messageID != "scenario-client-message" {
			t.Fatalf("Message ID = %q", messageID)
		}

		assertJSONSubset(t, "session.send request", map[string]any{
			"sessionId":     f.session.SessionID,
			"prompt":        "Use the hidden scenario context.",
			"displayPrompt": "Review selected scenario context",
			"mode":          "enqueue",
			"agentMode":     "interactive",
			"source":        "agent-scenario-client",
			"requestHeaders": map[string]any{
				"x-scenario-request": "wire-shape",
			},
		}, captured)

		attachments, ok := captured["attachments"].([]any)
		if !ok || len(attachments) != 6 {
			t.Fatalf("attachments = %#v", captured["attachments"])
		}
		wantTypes := []string{"file", "directory", "selection", "github_reference", "blob", "extension_context"}
		for i, want := range wantTypes {
			attachment := attachments[i].(map[string]any)
			if attachment["type"] != want {
				t.Fatalf("attachment %d type = %#v, want %q", i, attachment["type"], want)
			}
		}
		file := attachments[0].(map[string]any)
		assertJSONSubset(t, "file attachment", map[string]any{
			"path": `Q:\scenario-wire-file.txt`,
			"lineRange": map[string]any{
				"start": float64(3),
				"end":   float64(9),
			},
		}, file)
		extension := attachments[5].(map[string]any)
		assertJSONSubset(t, "extension attachment", map[string]any{
			"extensionId": "scenario-client:code-review",
			"canvasId":    "diff",
			"instanceId":  "diff-17",
			"payload": map[string]any{
				"selection": "SCENARIO_SELECTION",
				"line":      float64(17),
			},
		}, extension)
	})

	t.Run("should not invoke send when scenario cancels before dispatch", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
		defer cancel()
		f := newGeneratedRPCFixture(t, ctx)
		var calls atomic.Int64
		f.server.SetRequestHandler("session.send", func(json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
			calls.Add(1)
			return json.RawMessage(`{"messageId":"unexpected"}`), nil
		})

		cancelled, cancelSend := context.WithCancel(ctx)
		cancelSend()
		_, err := f.session.Send(cancelled, copilot.MessageOptions{
			Prompt:        "This message must never be invoked.",
			DisplayPrompt: "Cancelled scenario message",
			Source:        copilot.MessageSourceAgent("scenario-client"),
		})
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Send error = %v, want context cancellation", err)
		}
		if calls.Load() != 0 {
			t.Fatalf("session.send calls = %d, want 0", calls.Load())
		}
	})

	t.Run("should not replay scenario send after ambiguous transport loss", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
		defer cancel()
		f := newGeneratedRPCFixture(t, ctx)
		var calls atomic.Int64
		capturedRequests := make(chan map[string]any, 1)
		f.server.SetRequestHandler("session.send", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
			calls.Add(1)
			var captured map[string]any
			if err := json.Unmarshal(params, &captured); err != nil {
				return nil, &jsonrpc2.Error{Code: -32000, Message: err.Error()}
			}
			capturedRequests <- captured
			_ = f.conn.Close()
			return nil, nil
		})

		_, err := f.session.Send(ctx, copilot.MessageOptions{
			Prompt:        "AMBIGUOUS_SCENARIO_SEND",
			DisplayPrompt: "Ambiguous scenario send",
			Source:        copilot.MessageSourceAgent("scenario-client"),
		})
		if err == nil {
			t.Fatal("Expected transport loss")
		}
		if errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("Send waited for its deadline instead of reporting transport loss: %v", err)
		}
		if calls.Load() != 1 {
			t.Fatalf("session.send calls = %d, want 1", calls.Load())
		}
		var captured map[string]any
		select {
		case captured = <-capturedRequests:
		case <-ctx.Done():
			t.Fatalf("Timed out waiting for captured session.send request: %v", ctx.Err())
		}
		if _, exists := captured["mode"]; exists {
			t.Fatalf("Default mode should be omitted: %#v", captured)
		}
	})
}
