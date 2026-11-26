package rpc

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"

	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
)

var (
	_ *ModelSelectionPolicy                 = (*SessionModelGetSelectionPolicyResult)(nil)
	_ *SessionModelGetSelectionPolicyResult = (*ModelSelectionPolicy)(nil)
	_ interface {
		NotifyPendingPromptShown(context.Context, *PermissionPendingPromptShownNotification) (*PermissionsNotifyPromptShownResult, error)
	} = (*PermissionsAPI)(nil)
	_ = ClientGlobalAPIHandlers{
		AcpHandler(nil),
		EmbedderHandler(nil),
		EntraTokenHandler(nil),
		ExtensionLaunchProviderHandler(nil),
		GitHubTelemetryHandler(nil),
		GitHubTokenHandler(nil),
		HooksHandler(nil),
		HostHandler(nil),
		InstallationsHandler(nil),
		LlmInferenceHandler(nil),
		MCPHandler(nil),
		NetworkHandler(nil),
		RemoteHostsHandler(nil),
		UIThreadHandler(nil),
	}
	_ func(*jsonrpc2.Client, *ClientGlobalAPIHandlers) = RegisterClientGlobalAPIHandlers
	_ interface {
		Import(context.Context, *MemoryImportRequest) (*MemoryImportResult, error)
	} = (*ServerMemoriesAPI)(nil)
	_ interface {
		Import(context.Context, *SessionImportRequest) (*SessionImportResult, error)
	} = (*ServerSessionsAPI)(nil)
	_ ExternalToolResult      = ExternalToolStringResult("")
	_ ExternalToolResult      = (*ExternalToolTextResultForLlm)(nil)
	_ FilterMapping           = FilterMappingEnumMap{}
	_ FilterMapping           = ContentFilterModeMarkdown
	_ MCPServerConfig         = (*MCPServerConfigHTTP)(nil)
	_ MCPServerConfig         = (*MCPServerConfigStdio)(nil)
	_ UIElicitationFieldValue = UIElicitationStringValue("")
	_ UIElicitationFieldValue = UIElicitationStringArrayValue(nil)
	_ UIElicitationFieldValue = UIElicitationBooleanValue(false)
	_ UIElicitationFieldValue = UIElicitationNumberValue(0)
)

func TestInstalledPluginSourceLegacyLiterals(t *testing.T) {
	source := "owner/plugin"
	for _, test := range []struct {
		value any
		want  string
	}{
		{InstalledPluginSource{nil, nil, nil, &source}, `"owner/plugin"`},
		{SessionInstalledPluginSource{nil, nil, nil, &source}, `"owner/plugin"`},
		{InstalledPluginSourceGitHub{nil, nil, source, nil, "github"}, `{"repo":"owner/plugin","source":"github"}`},
		{InstalledPluginSourceLocal{"/plugins/local", "local"}, `{"path":"/plugins/local","source":"local"}`},
		{InstalledPluginSourceURL{nil, nil, nil, "url", "https://example.com/plugin.git"}, `{"source":"url","url":"https://example.com/plugin.git"}`},
		{SessionInstalledPluginSourceGitHub{nil, nil, source, nil, "github"}, `{"repo":"owner/plugin","source":"github"}`},
		{SessionInstalledPluginSourceLocal{"/plugins/local", "local"}, `{"path":"/plugins/local","source":"local"}`},
		{SessionInstalledPluginSourceURL{nil, nil, nil, "url", "https://example.com/plugin.git"}, `{"source":"url","url":"https://example.com/plugin.git"}`},
	} {
		wire, err := json.Marshal(test.value)
		if err != nil {
			t.Fatal(err)
		}
		if string(wire) != test.want {
			t.Errorf("%T wire = %s, want %s", test.value, wire, test.want)
		}
	}
}

func TestMCPDiscoveryFormattingRedactsConfigurationWithoutChangingJSON(t *testing.T) {
	const secret = "discovery-formatting-test-secret"
	var options MCPDiscoveryOptions
	optionsJSON := `{"additionalConfig":["{\"mcpServers\":{\"private\":{\"headers\":{\"Authorization\":\"` + secret + `\"}}}}"],"enabledServers":["visible-server"]}`
	var server DiscoveredMCPServer
	serverJSON := `{"configuration":{"type":"http","url":"https://example.com/mcp","headers":{"Authorization":"` + secret + `"},"tools":["*"]},"enabled":true,"name":"visible-server","source":"user"}`
	for _, tc := range []struct {
		name  string
		value any
		input string
	}{
		{"options", &options, optionsJSON},
		{"server", &server, serverJSON},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := json.Unmarshal([]byte(tc.input), tc.value); err != nil {
				t.Fatal(err)
			}
			for _, value := range []any{tc.value, reflect.ValueOf(tc.value).Elem().Interface(), struct{ Discovery any }{tc.value}} {
				for _, format := range []string{"%v", "%+v", "%#v"} {
					formatted := fmt.Sprintf(format, value)
					if strings.Contains(formatted, secret) || !strings.Contains(formatted, "<redacted>") {
						t.Fatalf("%s exposed configuration or omitted redaction: %s", format, formatted)
					}
					if !strings.Contains(formatted, "visible-server") {
						t.Fatalf("%s lost non-secret discovery fields: %s", format, formatted)
					}
				}
			}
			encoded, err := json.Marshal(tc.value)
			if err != nil {
				t.Fatal(err)
			}
			var got, want any
			if err := json.Unmarshal(encoded, &got); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal([]byte(tc.input), &want); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("diagnostic redaction changed wire configuration:\ngot: %s\nwant: %s", encoded, tc.input)
			}
		})
	}
}

func TestGeneratedRPCAPIShape(t *testing.T) {
	file, fileSet := parseGeneratedRPC(t)

	assertInterfaceType(t, file, "ExternalToolResult")
	assertTypeExpr(t, fileSet, findTypeSpec(t, file, "ExternalToolStringResult").Type, "string")
	assertStructFieldType(t, file, fileSet, "HandlePendingToolCallRequest", "Result", "ExternalToolResult")

	assertInterfaceType(t, file, "FilterMapping")
	assertTypeExpr(t, fileSet, findTypeSpec(t, file, "FilterMappingEnumMap").Type, "map[string]ContentFilterMode")

	assertInterfaceType(t, file, "MCPServerConfig")
	assertStructFieldType(t, file, fileSet, "MCPConfigAddRequest", "Config", "MCPSerializableServerConfig")
	assertStructFieldType(t, file, fileSet, "MCPConfigList", "Servers", "map[string]MCPSerializableServerConfig")
	assertStructFieldType(t, file, fileSet, "MCPConfigUpdateRequest", "Config", "MCPSerializableServerConfig")
	assertStructFieldType(t, file, fileSet, "MCPServerConfigHTTP", "FilterMapping", "FilterMapping")
	assertStructFieldType(t, file, fileSet, "MCPServerConfigStdio", "FilterMapping", "FilterMapping")
	assertStructFieldType(t, file, fileSet, "ModelSwitchToRequest", "AutoTier", "**AutoTier")
	assertStructFieldType(t, file, fileSet, "TaskClientUpdateProgress", "Percentage", "**float64")
	assertStructFieldType(t, file, fileSet, "TaskClientUpdateProgress", "Phase", "**string")
	assertTypeExpr(t, fileSet, findTypeSpec(t, file, "SubagentSettings").Type, "*SubagentSettingsConfig")
	assertStructFieldType(t, file, fileSet, "ToolsGetSubagentSettingsResult", "Subagents", "SubagentSettings")
	assertStructFieldType(t, file, fileSet, "UpdateSubagentSettingsRequest", "Subagents", "SubagentSettings")

	assertInterfaceType(t, file, "UIElicitationFieldValue")
	assertTypeExpr(t, fileSet, findTypeSpec(t, file, "UIElicitationStringArrayValue").Type, "[]string")
	assertStructFieldType(t, file, fileSet, "UIElicitationResponse", "Content", "map[string]UIElicitationFieldValue")
}

func TestSessionImportLegacyRequestWire(t *testing.T) {
	dryRun, name, directory := true, "imported", "/workspace"
	request := SessionImportRequest{"/fallback", &dryRun, "/input.jsonl", "input", &name, &directory}
	raw := captureParams(t, "sessions.import", func(ctx context.Context, srv *serverAPI, _ *sessionAPI) error {
		_, err := (*ServerSessionsAPI)(srv).Import(ctx, &request)
		return err
	})
	assertSessionImportParams(t, raw, map[string]any{
		"defaultWorkingDirectory": "/fallback", "dryRun": true, "inputPath": "/input.jsonl",
		"jsonl": "input", "name": "imported", "workingDirectory": "/workspace",
	})
}

func TestSessionImportOptionsWire(t *testing.T) {
	clientName, clientKind := "importer", "sdk"
	options := SessionImportOptions{
		DefaultWorkingDirectory: "/fallback", InputPath: "/input.jsonl", Jsonl: "input",
		ClientName: &clientName, ClientKind: &clientKind,
	}
	raw := captureParams(t, "sessions.import", func(ctx context.Context, srv *serverAPI, _ *sessionAPI) error {
		_, err := (*ServerSessionsAPI)(srv).ImportWithOptions(ctx, &options)
		return err
	})
	assertSessionImportParams(t, raw, map[string]any{
		"defaultWorkingDirectory": "/fallback", "inputPath": "/input.jsonl", "jsonl": "input",
		"clientName": "importer", "clientKind": "sdk",
	})
}

func TestSessionsImportSemanticLegacyRequestWire(t *testing.T) {
	clientName, dryRun, name, directory := "importer", false, "imported", "/workspace"
	request := SessionsImportSemanticRequest{
		&clientName, "/fallback", &dryRun, "/input.jsonl", "input", &name,
		map[string]any{"configDir": "/profile"}, &directory,
	}
	raw := captureParams(t, "sessions.importSemantic", func(ctx context.Context, srv *serverAPI, _ *sessionAPI) error {
		_, err := (*InternalServerSessionsAPI)(srv).ImportSemantic(ctx, &request)
		return err
	})
	assertSessionImportParams(t, raw, map[string]any{
		"clientName": "importer", "defaultWorkingDirectory": "/fallback", "dryRun": false,
		"inputPath": "/input.jsonl", "jsonl": "input", "name": "imported",
		"settings": map[string]any{"configDir": "/profile"}, "workingDirectory": "/workspace",
	})
}

func TestSessionsImportSemanticOptionsWire(t *testing.T) {
	clientName, clientKind, dryRun, name, directory := "importer", "cli", false, "imported", "/workspace"
	options := SessionsImportSemanticOptions{
		ClientName: &clientName, DefaultWorkingDirectory: "/fallback", DryRun: &dryRun,
		InputPath: "/input.jsonl", Jsonl: "input", Name: &name,
		Settings: map[string]any{"configDir": "/profile"}, WorkingDirectory: &directory,
	}
	want := map[string]any{
		"clientName": "importer", "defaultWorkingDirectory": "/fallback", "dryRun": false,
		"inputPath": "/input.jsonl", "jsonl": "input", "name": "imported",
		"settings": map[string]any{"configDir": "/profile"}, "workingDirectory": "/workspace",
	}
	for _, kind := range []*string{nil, &clientKind} {
		options.ClientKind = kind
		if kind != nil {
			want["clientKind"] = *kind
		}
		raw := captureParams(t, "sessions.importSemantic", func(ctx context.Context, srv *serverAPI, _ *sessionAPI) error {
			_, err := (*InternalServerSessionsAPI)(srv).ImportSemanticWithOptions(ctx, &options)
			return err
		})
		assertSessionImportParams(t, raw, want)
	}
}

func assertSessionImportParams(t *testing.T, raw json.RawMessage, want map[string]any) {
	t.Helper()
	var got map[string]any
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("sessions.import params = %#v, want %#v", got, want)
	}
}

func TestSubagentSettingsReadModifyWrite(t *testing.T) {
	for _, tc := range []struct {
		name     string
		settings string
	}{
		{"absent", ""},
		{"enabled", `,"contextManagementTools":true`},
		{"disabled", `,"contextManagementTools":false`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			input := []byte(`{"subagents":{"agents":{"rubber-duck":{"model":"model-id","providerId":"provider-id","autoInvoke":false}},"disabledSubagents":["explore"],"maxConcurrency":7,"maxDepth":3` + tc.settings + `}}`)
			var result ToolsGetSubagentSettingsResult
			if err := json.Unmarshal(input, &result); err != nil {
				t.Fatalf("decode getter result: %v", err)
			}
			if result.Subagents == nil || result.Subagents.Agents["rubber-duck"].AutoInvoke == nil {
				t.Fatalf("missing configured agent: %#v", result.Subagents)
			}
			*result.Subagents.Agents["rubber-duck"].AutoInvoke = true
			request := UpdateSubagentSettingsRequest{Subagents: result.Subagents}
			encoded, err := json.Marshal(request)
			if err != nil {
				t.Fatalf("encode setter request: %v", err)
			}
			expected := bytes.Replace(input, []byte(`"autoInvoke":false`), []byte(`"autoInvoke":true`), 1)
			var got, want any
			if err := json.Unmarshal(encoded, &got); err != nil {
				t.Fatalf("decode encoded request: %v", err)
			}
			if err := json.Unmarshal(expected, &want); err != nil {
				t.Fatalf("decode expected request: %v", err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("settings round trip:\ngot: %s\nwant: %s", encoded, expected)
			}
		})
	}
	var result ToolsGetSubagentSettingsResult
	if err := json.Unmarshal([]byte(`{"subagents":null}`), &result); err != nil {
		t.Fatalf("decode absent settings: %v", err)
	}
	if result.Subagents != nil {
		t.Fatalf("null decoded as settings: %#v", result.Subagents)
	}
}

func parseGeneratedRPC(t *testing.T) (*ast.File, *token.FileSet) {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate test file")
	}
	fileSet := token.NewFileSet()
	file, err := parser.ParseFile(fileSet, filepath.Join(filepath.Dir(currentFile), "zrpc.go"), nil, 0)
	if err != nil {
		t.Fatalf("parse zrpc.go: %v", err)
	}
	return file, fileSet
}

func findTypeSpec(t *testing.T, file *ast.File, typeName string) *ast.TypeSpec {
	t.Helper()
	for _, decl := range file.Decls {
		genDecl, ok := decl.(*ast.GenDecl)
		if !ok || genDecl.Tok != token.TYPE {
			continue
		}
		for _, spec := range genDecl.Specs {
			typeSpec, ok := spec.(*ast.TypeSpec)
			if ok && typeSpec.Name.Name == typeName {
				return typeSpec
			}
		}
	}
	t.Fatalf("type %s not found", typeName)
	return nil
}

func assertInterfaceType(t *testing.T, file *ast.File, typeName string) {
	t.Helper()
	if _, ok := findTypeSpec(t, file, typeName).Type.(*ast.InterfaceType); !ok {
		t.Fatalf("type %s has unexpected AST node %T", typeName, findTypeSpec(t, file, typeName).Type)
	}
}

func assertStructFieldType(t *testing.T, file *ast.File, fileSet *token.FileSet, structName, fieldName, want string) {
	t.Helper()
	structType, ok := findTypeSpec(t, file, structName).Type.(*ast.StructType)
	if !ok {
		t.Fatalf("type %s is %T, want struct", structName, findTypeSpec(t, file, structName).Type)
	}
	for _, field := range structType.Fields.List {
		for _, name := range field.Names {
			if name.Name == fieldName {
				assertTypeExpr(t, fileSet, field.Type, want)
				return
			}
		}
	}
	t.Fatalf("field %s.%s not found", structName, fieldName)
}

func assertTypeExpr(t *testing.T, fileSet *token.FileSet, expr ast.Expr, want string) {
	t.Helper()
	var buffer bytes.Buffer
	if err := format.Node(&buffer, fileSet, expr); err != nil {
		t.Fatalf("format type expression: %v", err)
	}
	if got := buffer.String(); got != want {
		t.Fatalf("type expression = %s, want %s", got, want)
	}
}
