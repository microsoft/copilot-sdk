package copilot

import (
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"testing"

	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func TestReleasedSessionOptionsPositionalWire(t *testing.T) {
	sources := &ExtensionSources{User: Bool(false), Project: Bool(true), Plugins: Bool(false)}
	create := CreateSessionOptions{
		SessionInferenceOptions{}, "legacy-session", "", "legacy-client", "", "", nil, sources, nil,
		CreateSessionFilesystemOptions{}, SessionStateOptions{}, CreateSessionInstructionOptions{},
		SessionHookOptions{}, SessionGitOptions{}, SessionSkillOptions{}, SessionToolOptions{},
		nil, SessionAgentOptions{}, SessionPermissionOptions{}, SessionMCPOptions{},
		SessionGitHubOptions{}, SessionInteractionOptions{}, "", SessionEventOptions{},
		SessionExecutionOptions{}, SessionExperimentOptions{}, nil, SessionPluginOptions{},
		nil, SessionCanvasOptions{}, SessionExtensionOptions{}, SessionPolicyOptions{},
	}
	resume := ResumeSessionOptions{
		SessionInferenceOptions{}, "legacy-client", SessionGitHubOptions{}, "", SessionToolOptions{},
		nil, SessionAgentOptions{}, SessionSkillOptions{}, SessionFilesystemOptions{},
		SessionExecutionOptions{}, SessionExperimentOptions{}, SessionInstructionOptions{},
		SessionGitOptions{}, SessionPermissionOptions{}, SessionMCPOptions{},
		SessionInteractionOptions{}, SessionHookOptions{}, "", "", nil, sources, nil, SessionStateOptions{},
		SessionEventOptions{}, nil, SessionPluginOptions{}, false, nil, nil,
		ResumeSessionCanvasOptions{}, SessionExtensionOptions{}, SessionPolicyOptions{},
	}
	client, requests, cleanup := newInMemoryClient(t)
	defer cleanup()
	session, err := client.CreateSession(t.Context(), &create)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Disconnect()
	resumed, err := client.ResumeSession(t.Context(), session.SessionID, &resume)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Disconnect()
	for _, method := range []string{"session.create", "session.resume"} {
		assertRequestMethod(t, requests.snapshot(), method)
		for _, request := range requests.snapshot() {
			if request.Method != method {
				continue
			}
			if request.Params["clientName"] != "legacy-client" {
				t.Fatalf("%s lost the legacy client name: %#v", method, request.Params)
			}
			if _, present := request.Params["sessionLifecycleMode"]; present {
				t.Fatalf("%s changed the legacy lifecycle default: %#v", method, request.Params)
			}
		}
	}
}

func TestClientGitHubOptionsRedactCredentials(t *testing.T) {
	options := ClientGitHubOptions{ClientID: "fixture-app", ClientSecret: "fixture-secret", Token: "fixture-token"}
	for _, verb := range []string{"%+v", "%#v"} {
		formatted := fmt.Sprintf(verb, ClientOptions{GitHub: options})
		if strings.Contains(formatted, options.ClientSecret) || strings.Contains(formatted, options.Token) {
			t.Errorf("%s exposed credentials in GitHub options", verb)
		}
		if !strings.Contains(formatted, options.ClientID) {
			t.Errorf("%s omitted the public client ID", verb)
		}
	}
}

func TestSessionLifecycleModeWireOwnership(t *testing.T) {
	for _, testCase := range []struct {
		name       string
		mode       SessionLifecycleMode
		nilOptions bool
	}{
		{name: "per-turn", mode: SessionLifecycleModePerTurn},
		{name: "interactive", mode: SessionLifecycleModeInteractive},
		{name: "host default"},
		{name: "nil options", mode: SessionLifecycleModePerTurn, nilOptions: true},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			client, requests, cleanup := newInMemoryClient(t)
			defer cleanup()
			var create *CreateSessionOptions
			var resume *ResumeSessionOptions
			var wantCreate *CreateSessionOptions
			var wantResume *ResumeSessionOptions
			if !testCase.nilOptions {
				create = &CreateSessionOptions{
					Events: SessionEventOptions{Streaming: Bool(false)},
				}
				resume = &ResumeSessionOptions{
					Events: SessionEventOptions{Streaming: Bool(false)},
				}
				wantCreate = &CreateSessionOptions{Events: SessionEventOptions{Streaming: Bool(false)}}
				wantResume = &ResumeSessionOptions{Events: SessionEventOptions{Streaming: Bool(false)}}
			}
			session, err := client.CreateSessionWithLifecycleMode(t.Context(), create, testCase.mode)
			if err != nil {
				t.Fatal(err)
			}
			defer session.Disconnect()
			resumed, err := client.ResumeSessionWithLifecycleMode(t.Context(), session.SessionID, resume, testCase.mode)
			if err != nil {
				t.Fatal(err)
			}
			defer resumed.Disconnect()
			for _, method := range []string{"session.create", "session.resume"} {
				recorded := requests.snapshot()
				assertRequestMethod(t, recorded, method)
				for _, request := range recorded {
					if request.Method != method {
						continue
					}
					got, present := request.Params["sessionLifecycleMode"]
					if testCase.mode == "" {
						if present {
							t.Fatalf("%s emitted an unset lifecycle mode: %#v", method, got)
						}
					} else if got != string(testCase.mode) {
						t.Fatalf("%s lifecycle mode = %#v, want %q", method, got, testCase.mode)
					}
					if !testCase.nilOptions && sessionWireGroup(request.Params, "events")["streaming"] != false {
						t.Fatalf("%s lost explicit false streaming: %#v", method, request.Params)
					}
				}
			}
			if !reflect.DeepEqual(create, wantCreate) || !reflect.DeepEqual(resume, wantResume) {
				t.Fatal("lifecycle selection changed caller-owned options")
			}
		})
	}
}

func TestClientFormattingRedactsGitHubOAuthSecret(t *testing.T) {
	options := ClientGitHubOptions{ClientID: "fixture-app", ClientSecret: "fixture-application-secret"}
	client := NewClient(&ClientOptions{Mode: ModeCodingAgent, GitHub: options})
	for _, verb := range []string{"%+v", "%#v"} {
		formatted := fmt.Sprintf(verb, client)
		if strings.Contains(formatted, options.ClientSecret) {
			t.Errorf("%s exposed the OAuth secret in the client", verb)
		}
		if !strings.Contains(formatted, options.ClientID) {
			t.Errorf("%s omitted the public client ID", verb)
		}
	}

	transport, server, _ := newRuntimeShutdownRpcPair(t)
	defer server.Stop()
	client.client = transport
	received := make(chan json.RawMessage, 1)
	server.SetRequestHandler("connect", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
		received <- params
		return json.RawMessage(`{"ok":true,"protocolVersion":4,"version":"test"}`), nil
	})
	if err := client.verifyProtocolVersion(t.Context()); err != nil {
		t.Fatal(err)
	}
	var params map[string]any
	if err := json.Unmarshal(<-received, &params); err != nil {
		t.Fatal(err)
	}
	if params["githubClientId"] != options.ClientID || params["githubClientSecret"] != options.ClientSecret {
		t.Error("connect did not send the configured OAuth pair")
	}
}

func TestGroupedSessionOptionsWireOwnership(t *testing.T) {
	client, requests, cleanup := newInMemoryClient(t)
	defer cleanup()
	options := &CreateSessionOptions{
		Tools:       SessionToolOptions{Definitions: []Tool{}, AvailableTools: []string{"allowed_tool", "excluded_tool"}, ExcludedTools: []string{"excluded_tool"}},
		Events:      SessionEventOptions{Streaming: Bool(false)},
		Filesystem:  CreateSessionFilesystemOptions{AdditionalDirectories: []string{}, TrackFileChanges: Bool(false)},
		MCP:         SessionMCPOptions{Servers: map[string]MCPServerConfig{}, DisabledServers: []string{}},
		Inference:   SessionInferenceOptions{AllowMultipleToolCalls: Bool(false)},
		Experiments: SessionExperimentOptions{Flags: map[string]bool{}, EnableExperimentalMode: Bool(false)},
		Permissions: SessionPermissionOptions{OnRequest: PermissionHandler.ApproveAll},
	}
	session, err := client.CreateSession(t.Context(), options)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Disconnect()
	var payload map[string]any
	recorded := requests.snapshot()
	assertRequestMethod(t, recorded, "session.create")
	for _, request := range recorded {
		if request.Method == "session.create" {
			payload = request.Params
		}
	}
	for group, want := range map[string]map[string]any{
		"tools":       {"definitions": []any{}, "availableTools": []any{"allowed_tool", "excluded_tool"}, "excludedTools": []any{"excluded_tool"}},
		"filesystem":  {"additionalDirectories": []any{}, "trackFileChanges": false},
		"mcp":         {"servers": map[string]any{}, "disabledServers": []any{}, "envValueMode": "direct"},
		"inference":   {"allowMultipleToolCalls": false},
		"experiments": {"flags": map[string]any{}, "enableExperimentalMode": false},
		"permissions": {"request": true},
	} {
		if got := sessionWireGroup(payload, group); !reflect.DeepEqual(got, want) {
			t.Errorf("%s = %#v, want %#v", group, got, want)
		}
	}
	if sessionWireGroup(payload, "events")["streaming"] != false {
		t.Fatalf("explicit false streaming was lost: %#v", payload)
	}
	for _, old := range []string{"requestPermission", "mcpServers", "streaming", "allowMultipleToolCalls", "featureFlags"} {
		if _, present := payload[old]; present {
			t.Errorf("legacy top-level field %s was emitted", old)
		}
	}
	if options.Tools.Definitions == nil || options.MCP.Servers == nil || options.Events.Streaming == nil || *options.Events.Streaming {
		t.Fatal("caller-owned options were changed")
	}
}

func TestForDefaultResolvesAtInvocation(t *testing.T) {
	t.Setenv(defaultConnectionEnvVar, "StDiO")
	first := ForDefault(&OwnedRuntimeOptions{BaseDirectory: "first"})
	t.Setenv(defaultConnectionEnvVar, "INPROCESS")
	second := ForDefault(&OwnedRuntimeOptions{BaseDirectory: "second", SessionIdleTimeoutSeconds: 12})
	if conn, ok := first.(SpawnedStdioConnection); !ok || conn.BaseDirectory != "first" || conn.SessionIdleTimeoutSeconds != 0 {
		t.Fatalf("stdio default = %#v", first)
	}
	if conn, ok := second.(InProcessConnection); !ok || conn.BaseDirectory != "second" || conn.SessionIdleTimeoutSeconds != 12 {
		t.Fatalf("in-process default = %#v", second)
	}
	t.Setenv(defaultConnectionEnvVar, "uri")
	defer func() {
		if value := recover(); value == nil || !strings.Contains(value.(string), defaultConnectionEnvVar) {
			t.Fatalf("invalid deployment default did not fail clearly: %v", value)
		}
	}()
	ForDefault(nil)
}

func TestDecodedProviderRoutingPreservesRequestPresence(t *testing.T) {
	for _, inferenceJSON := range []string{
		`{}`,
		`{"defaultProvider":null,"providers":[],"models":[]}`,
		`{"defaultProvider":"named"}`,
	} {
		t.Run(inferenceJSON, func(t *testing.T) {
			client, requests, cleanup := newInMemoryClient(t)
			defer cleanup()
			data := []byte(`{"inference":` + inferenceJSON + `}`)
			var createOptions CreateSessionOptions
			if err := json.Unmarshal(data, &createOptions); err != nil {
				t.Fatal(err)
			}
			createOptions.Permissions.OnRequest = PermissionHandler.ApproveAll
			session, err := client.CreateSession(t.Context(), &createOptions)
			if err != nil {
				t.Fatal(err)
			}
			defer session.Disconnect()
			var resumeOptions ResumeSessionOptions
			if err := json.Unmarshal(data, &resumeOptions); err != nil {
				t.Fatal(err)
			}
			resumeOptions.Permissions.OnRequest = PermissionHandler.ApproveAll
			resumed, err := client.ResumeSession(t.Context(), session.SessionID, &resumeOptions)
			if err != nil {
				t.Fatal(err)
			}
			defer resumed.Disconnect()
			var update rpc.SessionUpdateOptionsParams
			if err := json.Unmarshal(data, &update); err != nil {
				t.Fatal(err)
			}
			if _, err := resumed.RPC.Options.Update(t.Context(), &update); err != nil {
				t.Fatal(err)
			}
			var expected map[string]any
			if err := json.Unmarshal([]byte(inferenceJSON), &expected); err != nil {
				t.Fatal(err)
			}
			recorded := requests.snapshot()
			for _, method := range []string{"session.create", "session.resume", "session.options.update"} {
				assertRequestMethod(t, recorded, method)
			}
			for _, request := range recorded {
				if request.Method != "session.create" && request.Method != "session.resume" && request.Method != "session.options.update" {
					continue
				}
				actual := sessionWireGroup(request.Params, "inference")
				for _, key := range []string{"defaultProvider", "providers", "models"} {
					if request.Method == "session.options.update" && key != "defaultProvider" {
						continue
					}
					want, wantPresent := expected[key]
					got, gotPresent := actual[key]
					if gotPresent != wantPresent || !reflect.DeepEqual(got, want) {
						t.Errorf("%s %s = %#v (present=%t), want %#v (present=%t)", request.Method, key, got, gotPresent, want, wantPresent)
					}
				}
			}
		})
	}
}

func TestGroupedSDKRejectsProtocolThree(t *testing.T) {
	transport, server, _ := newRuntimeShutdownRpcPair(t)
	defer server.Stop()
	server.SetRequestHandler("connect", func(json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
		return json.RawMessage(`{"ok":true,"protocolVersion":3,"version":"old"}`), nil
	})
	client := &Client{
		client: transport, RPC: rpc.NewServerRPC(transport),
		internalRPC: rpc.NewInternalServerRPC(transport),
	}
	err := client.verifyProtocolVersion(t.Context())
	if err == nil || !strings.Contains(err.Error(), "SDK supports versions 4-4") || !strings.Contains(err.Error(), "server reports version 3") {
		t.Fatalf("old flat-wire runtime was not rejected clearly: %v", err)
	}
}
