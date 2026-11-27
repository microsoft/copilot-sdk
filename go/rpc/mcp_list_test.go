// Copyright (c) Microsoft Corporation. All rights reserved.

package rpc

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"testing"

	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
)

var _ interface {
	List(context.Context) (*MCPServerList, error)
	ListConfigured(context.Context) (*MCPConfiguredServerList, error)
} = (*MCPAPI)(nil)

// The legacy result name must stay assignable to the canonical save result.
var _ *MCPSecretSaveResult = (*MCPSecretsSaveResult)(nil)

func TestMCPSecretSaveDisplayRedactsWithoutChangingWire(t *testing.T) {
	request := MCPSecretSaveRequest{SecretID: "server/key", Value: "synthetic-mcp-credential"}
	for _, format := range []string{"%v", "%+v", "%#v"} {
		display := fmt.Sprintf(format, request)
		if strings.Contains(display, request.Value) || !strings.Contains(display, "<redacted>") {
			t.Errorf("%s exposes credential: %s", format, display)
		}
	}
	data, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	var wire map[string]string
	if err := json.Unmarshal(data, &wire); err != nil {
		t.Fatal(err)
	}
	if wire["value"] != request.Value || wire["secretId"] != request.SecretID {
		t.Fatalf("wire fields changed: %s", data)
	}
}

func TestMCPListUsesParameterlessWireContract(t *testing.T) {
	clientToServerReader, clientToServerWriter := io.Pipe()
	serverToClientReader, serverToClientWriter := io.Pipe()
	client := jsonrpc2.NewClient(clientToServerWriter, serverToClientReader)
	server := jsonrpc2.NewClient(serverToClientWriter, clientToServerReader)
	requests := make(chan map[string]any, 2)
	server.SetRequestHandler("session.mcp.list", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
		var request map[string]any
		if err := json.Unmarshal(params, &request); err != nil {
			return nil, &jsonrpc2.Error{Code: -32602, Message: err.Error()}
		}
		requests <- request
		return json.RawMessage(`{"servers":[]}`), nil
	})
	server.SetRequestHandler("session.mcp.listConfigured", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
		var request map[string]any
		if err := json.Unmarshal(params, &request); err != nil {
			return nil, &jsonrpc2.Error{Code: -32602, Message: err.Error()}
		}
		requests <- request
		return json.RawMessage(`{"servers":[
			{"name":"cold","enabled":true,"live":{"status":"not_configured"}},
			{"name":"old-connection","enabled":false,"live":{"status":"connected"}},
			{"name":"auth","enabled":true,"live":{"status":"needs-auth"}},
			{"name":"stopped","enabled":true,"live":{"status":"stopped"}},
			{"name":"failed","enabled":true,"live":{"status":"failed","error":"connection failed"}}
		]}`), nil
	})
	client.Start()
	server.Start()
	t.Cleanup(func() {
		client.Stop()
		server.Stop()
		_ = clientToServerWriter.Close()
		_ = clientToServerReader.Close()
		_ = serverToClientWriter.Close()
		_ = serverToClientReader.Close()
	})

	mcp := NewSessionRPC(client, "session-1").MCP
	if _, err := mcp.List(t.Context()); err != nil {
		t.Fatal(err)
	}
	configured, err := mcp.ListConfigured(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	want := []MCPServerStatus{"not_configured", "connected", "needs-auth", "stopped", "failed"}
	if len(configured.Servers) != len(want) {
		t.Fatalf("configured servers = %#v, want %d entries", configured.Servers, len(want))
	}
	for i, status := range want {
		live := configured.Servers[i].Live
		if live.Status != status {
			t.Fatalf("live status = %q, want %q", live.Status, status)
		}
	}
	if configured.Servers[1].Enabled || configured.Servers[4].Live.Error == nil ||
		*configured.Servers[4].Live.Error != "connection failed" {
		t.Fatalf("configuration enablement and runtime error were not preserved: %#v", configured.Servers)
	}
	got := make([]map[string]any, 2)
	for i := range got {
		got[i] = <-requests
	}
	for _, request := range got {
		if request["sessionId"] != "session-1" || len(request) != 1 {
			t.Fatalf("request = %#v, want only the bound session ID", request)
		}
	}
}
