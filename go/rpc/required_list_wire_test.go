// Copyright (c) Microsoft Corporation. All rights reserved.

package rpc

import (
	"context"
	"encoding/json"
	"net"
	"testing"
	"time"

	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
)

type wireCall func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error

// captureParams runs call against a fake runtime and returns the params it received for method.
func captureParams(t *testing.T, method string, call wireCall) json.RawMessage {
	t.Helper()
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()
	client := jsonrpc2.NewClient(clientConn, clientConn)
	server := jsonrpc2.NewClient(serverConn, serverConn)
	requests := make(chan json.RawMessage, 1)
	server.SetRequestHandler(method, func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
		requests <- params
		return json.RawMessage(`{}`), nil
	})
	client.Start()
	server.Start()
	defer client.Stop()
	defer server.Stop()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// Some results do not decode from {}; only the request matters here.
	_ = call(ctx, &serverAPI{client: client}, &sessionAPI{client: client, sessionID: "s1"})
	select {
	case raw := <-requests:
		return raw
	case <-ctx.Done():
		t.Fatalf("%s: no request received: %v", method, ctx.Err())
		return nil
	}
}

func wireField(t *testing.T, raw json.RawMessage, field string) (string, bool) {
	t.Helper()
	var params map[string]json.RawMessage
	if err := json.Unmarshal(raw, &params); err != nil {
		t.Fatalf("params %s: %v", raw, err)
	}
	value, ok := params[field]
	return string(value), ok
}

// One request per generated required-list emission shape (server struct copy, server
// multi-field with nested lists, session request map, top-level session RPC), called with
// the list left nil. go-codegen.test.ts owns which fields the generator coerces.
func TestRequiredListsNilSentAsEmpty(t *testing.T) {
	for _, test := range []struct {
		method string
		fields []string
		call   wireCall
	}{
		{"mcp.prepareInstall", []string{"inputs", "secrets"}, func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ServerMCPAPI)(srv).PrepareInstall(ctx, &MCPPrepareInstallRequest{})
			return err
		}},
		{"session.sendMessages", []string{"messages"}, func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := NewSessionRPC(ses.client, ses.sessionID).SendMessages(ctx, &SendMessagesRequest{})
			return err
		}},
		{"session.tools.set", []string{"tools"}, func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ToolsAPI)(ses).Set(ctx, &ToolsSetRequest{})
			return err
		}},
		{"sessions.checkInUse", []string{"sessionIds"}, func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ServerSessionsAPI)(srv).CheckInUse(ctx, &SessionsCheckInUseRequest{})
			return err
		}},
	} {
		t.Run(test.method, func(t *testing.T) {
			raw := captureParams(t, test.method, test.call)
			for _, field := range test.fields {
				if got, ok := wireField(t, raw, field); !ok || got != "[]" {
					t.Errorf("%s = %s (present %v), want []; params %s", field, got, ok, raw)
				}
			}
		})
	}
}

// Rows the nil-list coercion must leave as they are.
func TestRequestWireUnchanged(t *testing.T) {
	for _, test := range []struct {
		name    string
		method  string
		field   string
		want    string // "" means the key is absent
		call    wireCall
		rawWant string // compare the whole params instead of one field
	}{
		{name: "server empty list", method: "sessions.checkInUse", field: "sessionIds", want: `[]`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ServerSessionsAPI)(srv).CheckInUse(ctx, &SessionsCheckInUseRequest{SessionIDs: []string{}})
			return err
		}},
		{name: "server populated list", method: "sessions.checkInUse", field: "sessionIds", want: `["s1"]`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ServerSessionsAPI)(srv).CheckInUse(ctx, &SessionsCheckInUseRequest{SessionIDs: []string{"s1"}})
			return err
		}},
		{name: "server nil params", method: "sessions.checkInUse", rawWant: `null`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ServerSessionsAPI)(srv).CheckInUse(ctx, nil)
			return err
		}},
		{name: "session empty list", method: "session.tools.set", field: "tools", want: `[]`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ToolsAPI)(ses).Set(ctx, &ToolsSetRequest{Tools: []ProtocolExternalToolDefinition{}})
			return err
		}},
		{name: "session populated list", method: "session.plugins.enable", field: "names", want: `["p"]`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*PluginsAPI)(ses).Enable(ctx, &SessionPluginsEnableRequest{Names: []string{"p"}})
			return err
		}},
		{name: "session nil params", method: "session.tools.set", rawWant: `{"sessionId":"s1"}`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ToolsAPI)(ses).Set(ctx, nil)
			return err
		}},
		// A null map keeps the overrides and {} clears them, so required maps are not coerced.
		{name: "session nil required map", method: "session.telemetry.setFeatureOverrides", field: "features", want: `null`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*TelemetryAPI)(ses).SetFeatureOverrides(ctx, &TelemetrySetFeatureOverridesRequest{})
			return err
		}},
		{name: "session nil optional list", method: "session.model.setAllowedModels", field: "allowedModels", want: "", call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ModelAPI)(ses).SetAllowedModels(ctx, &ModelSetAllowedModelsRequest{})
			return err
		}},
		{name: "server nil map coerced by MarshalJSON", method: "shell.buildInteractiveEnvironment", field: "hostEnvironment", want: `{}`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*ServerShellAPI)(srv).BuildInteractiveEnvironment(ctx, &ShellBuildInteractiveEnvironmentRequest{})
			return err
		}},
		{name: "internal nil required list", method: "cloudTasks.resolveTaskId", field: "ignoredLocalSessionIds", want: `null`, call: func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
			_, err := (*InternalServerCloudTasksAPI)(&internalServerAPI{client: srv.client}).ResolveTaskId(ctx, &CloudTasksResolveTaskIDParams{})
			return err
		}},
	} {
		t.Run(test.name, func(t *testing.T) {
			raw := captureParams(t, test.method, test.call)
			if test.rawWant != "" {
				if string(raw) != test.rawWant {
					t.Errorf("params = %s, want %s", raw, test.rawWant)
				}
				return
			}
			got, ok := wireField(t, raw, test.field)
			if test.want == "" {
				if ok {
					t.Errorf("%s = %s, want it absent", test.field, got)
				}
				return
			}
			if got != test.want {
				t.Errorf("%s = %s (present %v), want %s; params %s", test.field, got, ok, test.want, raw)
			}
		})
	}
}

func TestRequiredListNilLeavesCallerRequest(t *testing.T) {
	request := &SessionsCheckInUseRequest{}
	captureParams(t, "sessions.checkInUse", func(ctx context.Context, srv *serverAPI, ses *sessionAPI) error {
		_, err := (*ServerSessionsAPI)(srv).CheckInUse(ctx, request)
		return err
	})
	if request.SessionIDs != nil {
		t.Errorf("caller's SessionIDs = %#v, want nil", request.SessionIDs)
	}
}
