package rpc

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os/exec"
	"reflect"
	"testing"
	"time"

	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
)

func TestCommandsExternalConsumer(t *testing.T) {
	output, err := exec.CommandContext(t.Context(), "go", "run", "../testdata/commands_compat").CombinedOutput()
	if err != nil {
		t.Fatalf("external command consumer failed: %v\n%s", err, output)
	}
}

// Existing catalog requests retain their optional pointer fields and keyed literals.
func TestLegacyParameterRequestsKeepKeyedLiterals(t *testing.T) {
	contract := CatalogClientContract{ProtocolVersion: 3, RequiredCapabilities: []string{"mcp-install-planning"}}
	scope := MCPPlanScopeUser
	limit := int32(4)

	existing := []any{
		MCPPlanInstallRequest{
			Contract: contract,
			Source:   &MCPPlanInstallSourceCandidate{CandidateHandle: "candidate", SearchID: "search"},
			Scope:    &scope,
		},
		CatalogSearchRequest{Contract: contract, Query: "catalogue query", Limit: &limit},
	}
	for _, request := range existing {
		wire := encodeRequest(t, request)
		if _, ok := wire["policySessionId"]; ok {
			t.Fatalf("existing literal %T sent policySessionId: %v", request, wire)
		}
	}

	session := "session"
	added := encodeRequest(t, MCPPlanInstallRequest{
		Contract:        contract,
		Source:          &MCPPlanInstallSourceCandidate{CandidateHandle: "candidate", SearchID: "search"},
		Scope:           &scope,
		PolicySessionID: &session,
	})
	if added["policySessionId"] != session || added["scope"] != "user" {
		t.Fatalf("added input not encoded alongside the legacy fields: %v", added)
	}
}

func TestExternalToolDefinitionPreservesMetadata(t *testing.T) {
	definition := ProtocolExternalToolDefinition{
		Name:        "lookup",
		Description: "Description",
		Metadata:    map[string]any{"custom": "kept"},
	}
	data, err := json.Marshal(definition)
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"description":"Description","metadata":{"custom":"kept"},"name":"lookup"}`; string(data) != want {
		t.Fatalf("definition = %s, want %s", data, want)
	}
}

// Explicit type arguments preserve signature checks without redundant variable types.
func requireMethodSignature[T any](method T) T {
	return method
}

func TestMCPConfigRequestsPreserveWireFields(t *testing.T) {
	requireMethodSignature[func(*ServerMCPConfigAPI, context.Context, *MCPConfigAddRequest) (*MCPConfigAddResult, error)]((*ServerMCPConfigAPI).Add)
	requireMethodSignature[func(*ServerMCPConfigAPI, context.Context, *MCPConfigRemoveRequest) (*MCPConfigRemoveResult, error)]((*ServerMCPConfigAPI).Remove)
	config := RawMCPSerializableServerConfigData{Raw: json.RawMessage(`{"command":"echo"}`)}
	root := "selected-root"
	for _, entry := range []struct {
		request any
		want    map[string]any
	}{
		{MCPConfigAddRequest{config, nil, "legacy"}, map[string]any{"config": map[string]any{"command": "echo"}, "name": "legacy"}},
		{MCPConfigRemoveRequest{nil, nil, "legacy"}, map[string]any{"name": "legacy"}},
		{MCPConfigAddRequest{Config: config, ConfigDir: &root, Name: "legacy"}, map[string]any{"config": map[string]any{"command": "echo"}, "configDir": root, "name": "legacy"}},
		{MCPConfigRemoveRequest{ConfigDir: &root, Name: "legacy"}, map[string]any{"configDir": root, "name": "legacy"}},
	} {
		if wire := encodeRequest(t, entry.request); !reflect.DeepEqual(wire, entry.want) {
			t.Fatalf("legacy MCP request %T = %v, want %v", entry.request, wire, entry.want)
		}
	}
}

func TestMCPConfigOptionsUseSelectedRoot(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()
	client := jsonrpc2.NewClient(clientConn, clientConn)
	server := jsonrpc2.NewClient(serverConn, serverConn)
	requests := make(chan json.RawMessage, 10)
	handler := func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
		requests <- params
		return json.RawMessage(`null`), nil
	}
	server.SetRequestHandler("mcp.config.add", handler)
	server.SetRequestHandler("mcp.config.remove", handler)
	client.Start()
	server.Start()
	defer client.Stop()
	defer server.Stop()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	api := NewServerRPC(client).MCP.Config()
	root := "selected-root"
	emptyRoot := ""
	var nullAdd MCPConfigAddOptions
	var nullRemove MCPConfigRemoveOptions
	if err := json.Unmarshal([]byte(`{"config":{"command":"echo"},"configDir":null,"name":"legacy"}`), &nullAdd); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(`{"configDir":null,"name":"legacy"}`), &nullRemove); err != nil {
		t.Fatal(err)
	}
	if nullAdd.ConfigDir != nil || nullRemove.ConfigDir != nil {
		t.Fatal("null configDir must select the default root")
	}
	metadataURL := "https://example.com/client.json"
	config := RawMCPSerializableServerConfigData{Raw: json.RawMessage(`{"command":"echo"}`)}
	add := requireMethodSignature[func(context.Context, *MCPConfigAddRequest) (*MCPConfigAddResult, error)](api.Add)
	remove := requireMethodSignature[func(context.Context, *MCPConfigRemoveRequest) (*MCPConfigRemoveResult, error)](api.Remove)
	if _, err := add(ctx, &MCPConfigAddRequest{config, &root, "legacy"}); err != nil {
		t.Fatal(err)
	}
	if _, err := remove(ctx, &MCPConfigRemoveRequest{&metadataURL, &root, "legacy"}); err != nil {
		t.Fatal(err)
	}
	wantRequests := []map[string]any{
		{"config": map[string]any{"command": "echo"}, "configDir": root, "name": "legacy"},
		{"authClientIdMetadataUrl": metadataURL, "configDir": root, "name": "legacy"},
	}
	for _, directory := range []*string{nil, nullAdd.ConfigDir, &emptyRoot, &root} {
		addOptions := MCPConfigAddOptions{Config: config, ConfigDir: directory, Name: "legacy"}
		removeOptions := MCPConfigRemoveOptions{AuthClientIDMetadataURL: &metadataURL, ConfigDir: directory, Name: "legacy"}
		beforeAdd, beforeRemove := encodeRequest(t, addOptions), encodeRequest(t, removeOptions)
		if _, err := api.AddWithOptions(ctx, &addOptions); err != nil {
			t.Fatal(err)
		}
		if _, err := api.RemoveWithOptions(ctx, &removeOptions); err != nil {
			t.Fatal(err)
		}
		if after := encodeRequest(t, addOptions); !reflect.DeepEqual(after, beforeAdd) {
			t.Fatalf("AddWithOptions changed caller input: got %v, want %v", after, beforeAdd)
		}
		if after := encodeRequest(t, removeOptions); !reflect.DeepEqual(after, beforeRemove) {
			t.Fatalf("RemoveWithOptions changed caller input: got %v, want %v", after, beforeRemove)
		}
		wantAdd := map[string]any{"config": map[string]any{"command": "echo"}, "name": "legacy"}
		wantRemove := map[string]any{"authClientIdMetadataUrl": metadataURL, "name": "legacy"}
		if directory != nil {
			wantAdd["configDir"], wantRemove["configDir"] = *directory, *directory
		}
		wantRequests = append(wantRequests, wantAdd, wantRemove)
	}
	for _, want := range wantRequests {
		select {
		case raw := <-requests:
			var actual map[string]any
			if err := json.Unmarshal(raw, &actual); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(actual, want) {
				t.Fatalf("MCP options = %v, want %v", actual, want)
			}
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	}
}

func TestMCPConfigOptionalRequestsUseObjectParams(t *testing.T) {
	root := "selected-root"
	for _, test := range []struct {
		name   string
		method string
		call   func(context.Context, *InternalServerMCPConfigAPI) error
		want   string
	}{
		{"files nil", "mcp.config.files", func(ctx context.Context, api *InternalServerMCPConfigAPI) error {
			_, err := api.Files(ctx, nil)
			return err
		}, `{}`},
		{"files selected", "mcp.config.files", func(ctx context.Context, api *InternalServerMCPConfigAPI) error {
			_, err := api.Files(ctx, &MCPConfigFilesRequest{ConfigDir: &root})
			return err
		}, `{"configDir":"selected-root"}`},
		{"read nil", "mcp.config.read", func(ctx context.Context, api *InternalServerMCPConfigAPI) error {
			_, err := api.Read(ctx, nil)
			return err
		}, `{}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			clientConn, serverConn := net.Pipe()
			defer clientConn.Close()
			defer serverConn.Close()
			client := jsonrpc2.NewClient(clientConn, clientConn)
			server := jsonrpc2.NewClient(serverConn, serverConn)
			requests := make(chan json.RawMessage, 1)
			server.SetRequestHandler(test.method, func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
				requests <- params
				if test.method == "mcp.config.files" {
					return json.RawMessage(`{"files":[]}`), nil
				}
				return json.RawMessage(`{}`), nil
			})
			client.Start()
			server.Start()
			defer client.Stop()
			defer server.Stop()
			if err := test.call(t.Context(), NewInternalServerRPC(client).MCP.Config()); err != nil {
				t.Fatal(err)
			}
			if got := <-requests; string(got) != test.want {
				t.Fatalf("wire params = %s, want %s", got, test.want)
			}
		})
	}
}

func TestPermissionRequiredOptionsOnWire(t *testing.T) {
	for _, provider := range []bool{false, true} {
		for _, required := range []bool{false, true} {
			t.Run(fmt.Sprintf("provider=%t/required=%t", provider, required), func(t *testing.T) {
				clientConn, serverConn := net.Pipe()
				defer clientConn.Close()
				defer serverConn.Close()
				client := jsonrpc2.NewClient(clientConn, clientConn)
				server := jsonrpc2.NewClient(serverConn, serverConn)
				requests := make(chan json.RawMessage, 1)
				server.SetRequestHandler("session.permissions.setRequired", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
					requests <- params
					return json.RawMessage(`{"success":true}`), nil
				})
				client.Start()
				server.Start()
				defer client.Stop()
				defer server.Stop()
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				api := NewSessionRPC(client, "permission-session").Permissions
				expected := map[string]any{"sessionId": "permission-session", "required": required}
				var result *PermissionsSetRequiredResult
				var err error
				if provider {
					providerID := "provider"
					options := PermissionsSetRequiredRequest{Required: required, ProviderID: &providerID}
					result, err = api.SetRequired(ctx, &options)
					expected["providerId"] = providerID
				} else {
					result, err = api.SetRequired(ctx, &PermissionsSetRequiredRequest{Required: required})
				}
				if err != nil {
					t.Fatal(err)
				}
				if result == nil || !result.Success {
					t.Fatalf("permission requirement result = %#v", result)
				}
				select {
				case raw := <-requests:
					var actual map[string]any
					if err := json.Unmarshal(raw, &actual); err != nil {
						t.Fatal(err)
					}
					if !reflect.DeepEqual(actual, expected) {
						t.Fatalf("permission requirement = %v, want %v", actual, expected)
					}
				case <-ctx.Done():
					t.Fatal(ctx.Err())
				}
			})
		}
	}
}

func TestSessionInfoPreservesOptionalMetadata(t *testing.T) {
	data := SessionInfoData{InfoType: "notification", Message: "message"}
	wire := encodeRequest(t, data)
	if len(wire) != 2 || wire["infoType"] != "notification" || wire["message"] != "message" {
		t.Fatalf("info payload changed: %v", wire)
	}
	tip, url := "a tip", "https://example.com"
	data = SessionInfoData{InfoType: "notification", Message: "message", Tip: &tip, URL: &url}
	wire = encodeRequest(t, data)
	if wire["tip"] != tip || wire["url"] != url {
		t.Fatalf("optional info metadata changed: %v", wire)
	}
}

func TestSessionInfoDecodesWithInternalReasoningMetadata(t *testing.T) {
	var event SessionEvent
	err := json.Unmarshal([]byte(`{
		"type":"session.info",
		"data":{
			"infoType":"reasoning_effort","message":"Reasoning effort changed",
			"tip":"a tip","url":"https://example.com",
			"responsesReasoning":{"model":"gpt-6-astra","initialEffort":"low","effort":"high"}
		}
	}`), &event)
	if err != nil {
		t.Fatal(err)
	}
	data, ok := event.Data.(*SessionInfoData)
	if !ok || data.InfoType != "reasoning_effort" || data.Message != "Reasoning effort changed" ||
		data.Tip == nil || *data.Tip != "a tip" || data.URL == nil || *data.URL != "https://example.com" ||
		data.ResponsesReasoning == nil || data.ResponsesReasoning.Model != "gpt-6-astra" ||
		data.ResponsesReasoning.InitialEffort != "low" || data.ResponsesReasoning.Effort != "high" {
		t.Fatalf("info event with internal metadata changed: %#v", event.Data)
	}
}

func TestLoginBeginOptionsOnWire(t *testing.T) {
	for _, name := range []string{"legacy", "device-code", "nil"} {
		t.Run(name, func(t *testing.T) {
			clientConn, serverConn := net.Pipe()
			defer clientConn.Close()
			defer serverConn.Close()
			client := jsonrpc2.NewClient(clientConn, clientConn)
			server := jsonrpc2.NewClient(serverConn, serverConn)
			requests := make(chan json.RawMessage, 1)
			server.SetRequestHandler("session.accounts.login.begin", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
				requests <- params
				return json.RawMessage(`{"flowId":"flow","step":{"kind":"complete","success":true}}`), nil
			})
			client.Start()
			server.Start()
			defer client.Stop()
			defer server.Stop()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			api := NewSessionRPC(client, "login-session").Accounts.Login()
			var err error
			expected := map[string]any{"sessionId": "login-session"}
			switch name {
			case "legacy":
				accountID := "cached-account"
				mode := AuthLoginModeDefault
				_, err = api.Begin(ctx, &AuthLoginBeginRequest{
					AccountID: &accountID,
					Kind:      LoginProviderKindGitHub,
					Mode:      &mode,
				})
				expected["accountId"] = accountID
				expected["kind"] = "gitHub"
				expected["mode"] = "default"
			case "device-code":
				method := AuthLoginMethodDeviceCode
				_, err = api.Begin(ctx, &AuthLoginBeginRequest{
					Kind: LoginProviderKindGitHub, Method: &method,
				})
				expected["kind"] = "gitHub"
				expected["method"] = "device-code"
			case "nil":
				_, err = api.Begin(ctx, nil)
			}
			if err != nil {
				t.Fatal(err)
			}
			select {
			case raw := <-requests:
				var actual map[string]any
				if err := json.Unmarshal(raw, &actual); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(actual, expected) {
					t.Fatalf("login request = %v, want %v", actual, expected)
				}
			case <-ctx.Done():
				t.Fatal(ctx.Err())
			}
		})
	}
}

func TestPluginsListOnWire(t *testing.T) {
	var _ interface {
		List(context.Context, *PluginsListRequest) (*PluginListResult, error)
	} = (*ServerPluginsAPI)(nil)

	for _, name := range []string{"nil-options", "context"} {
		t.Run(name, func(t *testing.T) {
			clientConn, serverConn := net.Pipe()
			defer clientConn.Close()
			defer serverConn.Close()
			client := jsonrpc2.NewClient(clientConn, clientConn)
			server := jsonrpc2.NewClient(serverConn, serverConn)
			requests := make(chan json.RawMessage, 1)
			server.SetRequestHandler("plugins.list", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
				requests <- params
				return json.RawMessage(`{"plugins":[]}`), nil
			})
			client.Start()
			server.Start()
			defer client.Stop()
			defer server.Stop()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			api := NewServerRPC(client).Plugins
			expected := map[string]any{}
			var result *PluginListResult
			var err error
			switch name {
			case "context":
				directory := t.TempDir()
				result, err = api.List(ctx, &PluginsListRequest{
					WorkingDirectory:  &directory,
					PluginDirectories: []string{directory},
				})
				expected["workingDirectory"] = directory
				expected["pluginDirectories"] = []any{directory}
			default:
				result, err = api.List(ctx, nil)
			}
			if err != nil {
				t.Fatal(err)
			}
			if result == nil || result.Plugins == nil || len(result.Plugins) != 0 {
				t.Fatalf("plugin listing result = %#v", result)
			}
			select {
			case raw := <-requests:
				var actual map[string]any
				if err := json.Unmarshal(raw, &actual); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(actual, expected) {
					t.Fatalf("plugin listing params = %s, want %v", raw, expected)
				}
			case <-ctx.Done():
				t.Fatal(ctx.Err())
			}
		})
	}
}

func encodeRequest(t *testing.T, request any) map[string]any {
	t.Helper()
	data, err := json.Marshal(request)
	if err != nil {
		t.Fatalf("marshal %T: %v", request, err)
	}
	var wire map[string]any
	if err := json.Unmarshal(data, &wire); err != nil {
		t.Fatalf("unmarshal %T: %v", request, err)
	}
	return wire
}
