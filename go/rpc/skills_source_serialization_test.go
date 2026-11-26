// Copyright (c) Microsoft Corporation. All rights reserved.

package rpc

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"reflect"
	"slices"
	"testing"
	"time"

	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
)

func TestLegacyInstalledPluginSourcesPreserveMainDecoding(t *testing.T) {
	for _, test := range []struct {
		wire string
		want string
	}{
		{`{"source":"unknown","repo":"owner/plugin","metadata":true}`, `{"repo":"owner/plugin","source":"unknown"}`},
		{`{"source":"github","metadata":true}`, `{"repo":"","source":"github"}`},
	} {
		t.Run(test.wire, func(t *testing.T) {
			var source InstalledPluginSource
			var sessionSource SessionInstalledPluginSource
			for _, target := range []any{&source, &sessionSource} {
				if err := json.Unmarshal([]byte(test.wire), target); err != nil {
					t.Fatalf("%T rejected a source accepted by main: %v", target, err)
				}
				wire, err := json.Marshal(target)
				if err != nil {
					t.Fatal(err)
				}
				if string(wire) != test.want {
					t.Errorf("%T encoded as %s; want main's %s", target, wire, test.want)
				}
			}
		})
	}
}

func TestLegacyInstalledPluginSourceDiscriminators(t *testing.T) {
	for _, test := range []struct {
		name, wire, want string
	}{
		{"incomplete GitHub", `{"source":"github"}`, "github"},
		{"future source", `{"source":"future","metadata":true}`, "future"},
		{"JSON-shaped discriminator", `{"source":"{\"source\":\"future\"}","repo":"owner/plugin"}`, `{"source":"future"}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			var source InstalledPluginSource
			if err := json.Unmarshal([]byte(test.wire), &source); err != nil {
				t.Fatal(err)
			}
			if source.InstalledPluginSourceGitHub == nil {
				t.Fatalf("legacy source has no GitHub member: %+v", source)
			}
			if got := string(source.InstalledPluginSourceGitHub.Source); got != test.want {
				t.Errorf("legacy discriminator = %q; want %q", got, test.want)
			}
			var sessionSource SessionInstalledPluginSource
			if err := json.Unmarshal([]byte(test.wire), &sessionSource); err != nil {
				t.Fatal(err)
			}
			if sessionSource.SessionInstalledPluginSourceGitHub == nil {
				t.Fatalf("legacy session source has no GitHub member: %+v", sessionSource)
			}
			if got := string(sessionSource.SessionInstalledPluginSourceGitHub.Source); got != test.want {
				t.Errorf("legacy session discriminator = %q; want %q", got, test.want)
			}
			preview := ToInstalledPluginWithRawSource(InstalledPlugin{Source: &source})
			if preview.Source.Raw != nil || preview.Source.InstalledPluginSourceGitHub != source.InstalledPluginSourceGitHub {
				t.Errorf("conversion reinterpreted the typed source: %+v", preview.Source)
			}
		})
	}
}

func TestUISnapshotPreservesLegacySourceDecoding(t *testing.T) {
	for _, test := range []struct {
		name, path, field, source, want string
	}{
		{"active incomplete", "plugins", "source", `{"source":"github"}`, `{"repo":"","source":"github"}`},
		{"inventory future", "inventory", "source", `{"source":"future","metadata":true}`, `{"repo":"","source":"future"}`},
		{"explicit installed source", "explicit", "installed_source", `{"source":"github","metadata":true}`, `{"repo":"","source":"github"}`},
		{"ambient future", "ambient", "source", `{"source":"future","metadata":true}`, `{"repo":"","source":"future"}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			plugin := map[string]any{
				"name": "plugin", "marketplace": "", "enabled": true,
				"installed_at": "2026-11-26T00:00:00Z", test.field: json.RawMessage(test.source),
			}
			activation := map[string]any{
				"context": map[string]any{
					"requestedWorkingDirectory": "/workspace", "resolvedWorkingDirectory": "/workspace",
					"settingsRoot": "/config", "trusted": true, "trustSource": "fixture",
				},
				"fingerprint": "fixture", "inventory": []any{}, "plugins": []any{}, "warnings": []any{},
			}
			policy := map[string]any{
				"explicitPlugins": []any{}, "includeAmbient": false, "trust": map[string]any{"mode": "trusted"},
			}
			switch test.path {
			case "plugins":
				activation["plugins"] = []any{plugin}
			case "inventory":
				activation["inventory"] = []any{map[string]any{
					"active": true, "persisted": true, "plugin": plugin, "reason": "fixture",
				}}
			case "explicit":
				policy["explicitPlugins"] = []any{plugin}
			case "ambient":
				policy["ambientPlugins"] = []any{plugin}
			}
			response, err := json.Marshal(map[string]any{
				"agentTurnActive": false, "initializingServiceCount": 0,
				"pendingElicitationRequests": []any{}, "pendingExitPlanModeRequests": []any{},
				"pendingUserInputRequests": []any{}, "processing": false, "serviceTasks": []any{},
				"sidekickBackgroundTasks": []any{}, "startupPrompts": []any{}, "supportsMcpApps": false,
				"pluginActivationSnapshot": activation, "pluginActivationPolicy": policy,
			})
			if err != nil {
				t.Fatal(err)
			}
			clientConn, serverConn := net.Pipe()
			defer clientConn.Close()
			defer serverConn.Close()
			client := jsonrpc2.NewClient(clientConn, clientConn)
			server := jsonrpc2.NewClient(serverConn, serverConn)
			requests := make(chan json.RawMessage, 1)
			server.SetRequestHandler("session.ui.snapshot", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
				requests <- params
				return response, nil
			})
			client.Start()
			server.Start()
			defer client.Stop()
			defer server.Stop()
			snapshot, err := NewSessionRPC(client, "fixture-session").UI.Snapshot(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			select {
			case request := <-requests:
				if string(request) != `{"sessionId":"fixture-session"}` {
					t.Fatalf("snapshot request = %s", request)
				}
			default:
				t.Fatal("snapshot returned without a fixture response")
			}
			var installed InstalledPlugin
			switch test.path {
			case "plugins":
				installed = snapshot.PluginActivationSnapshot.Plugins[0]
			case "inventory":
				installed = snapshot.PluginActivationSnapshot.Inventory[0].Plugin
			case "explicit":
				installed = snapshot.PluginActivationPolicy.ExplicitPlugins[0]
			case "ambient":
				installed = snapshot.PluginActivationPolicy.AmbientPlugins[0]
			}
			source := installed.Source
			if test.field == "installed_source" {
				source = installed.InstalledSource
			}
			wire, err := json.Marshal(source)
			if err != nil {
				t.Fatal(err)
			}
			if string(wire) != test.want {
				t.Errorf("snapshot source = %s; want main's %s", wire, test.want)
			}
		})
	}
}

func TestUIPluginActivationSnapshotSourcesCanBeUsedInPreview(t *testing.T) {
	const unmatched = `{"source":"github"}`
	const typed = `{"repo":"owner/plugin","source":"github"}`
	var snapshot UISnapshot
	if err := json.Unmarshal([]byte(`{"pluginActivationSnapshot":{"plugins":[{"name":"plugin","source":`+unmatched+`,"installed_source":`+typed+`}]}}`), &snapshot); err != nil {
		t.Fatal(err)
	}
	preview := MCPDiscoverPluginServersRequest{
		InstalledPlugins: ToInstalledPluginsWithRawSource(snapshot.PluginActivationSnapshot.Plugins),
	}
	source := preview.InstalledPlugins[0].Source
	if source.Raw != nil || source.InstalledPluginSourceGitHub == nil || source.InstalledPluginSourceGitHub.Source != "github" {
		t.Errorf("preview did not preserve the legacy typed source: %+v", source)
	}
	installed := preview.InstalledPlugins[0].InstalledSource
	if installed.InstalledPluginSourceGitHub == nil || installed.InstalledPluginSourceGitHub.Repo != "owner/plugin" {
		t.Errorf("preview typed GitHub provenance not preserved: %+v", installed)
	}
}

func TestInstalledPluginSourcePositionalLiterals(t *testing.T) {
	// Published source unions can be constructed with four positional fields.
	slug := `{"source":"future"}`
	for _, source := range []any{
		InstalledPluginSource{nil, nil, nil, &slug},
		SessionInstalledPluginSource{nil, nil, nil, &slug},
	} {
		wire, err := json.Marshal(source)
		if err != nil {
			t.Fatal(err)
		}
		if want := `"{\"source\":\"future\"}"`; string(wire) != want {
			t.Errorf("%T encoded as %s, want %s", source, wire, want)
		}
	}
}

func TestInstalledPluginSourceWithRawUnknownObject(t *testing.T) {
	const wire = `{"source":"future","metadata":{"origin":"catalog"}}`
	var source InstalledPluginSourceWithRaw
	if err := json.Unmarshal([]byte(wire), &source); err != nil {
		t.Fatal(err)
	}
	got, err := json.Marshal(source)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != wire {
		t.Errorf("source encoded as %s, want %s", got, wire)
	}
}

func TestInstalledPluginSourceWithRawCopies(t *testing.T) {
	const unknown = `{"source":"unknown","repo":"owner/plugin","metadata":true}`
	const incomplete = `{"source":"github","metadata":true}`
	var scan PluginsScanDirectoriesResult
	if err := json.Unmarshal([]byte(`{"plugins":[{"name":"plugin","marketplace":"","enabled":true,"installed_at":"2026-11-18T00:00:00Z","source":`+unknown+`,"installed_source":`+incomplete+`}],"directories":[],"warnings":[]}`), &scan); err != nil {
		t.Fatal(err)
	}
	preview := MCPDiscoverPluginServersRequest{InstalledPlugins: scan.Plugins}
	load := SkillsLoadRequest{AdditionalPlugins: preview.InstalledPlugins}
	copies := []struct {
		value InstalledPluginSourceWithRaw
		want  string
	}{
		{*load.AdditionalPlugins[0].Source, unknown},
		{*load.AdditionalPlugins[0].InstalledSource, incomplete},
	}
	*scan.Plugins[0].Source = InstalledPluginSourceWithRaw{}
	*scan.Plugins[0].InstalledSource = InstalledPluginSourceWithRaw{}
	for _, copy := range copies {
		wire, err := json.Marshal(copy.value)
		if err != nil {
			t.Fatal(err)
		}
		if string(wire) != copy.want {
			t.Errorf("copied source = %s, want %s", wire, copy.want)
		}
	}
}

func ExampleInstalledPluginSourceWithRaw() {
	source := "owner/plugin"
	legacy, err := json.Marshal(InstalledPluginSource{nil, nil, nil, &source})
	if err != nil {
		panic(err)
	}
	var lossless InstalledPluginSourceWithRaw
	if err := json.Unmarshal([]byte(`{"source":"unknown","metadata":true}`), &lossless); err != nil {
		panic(err)
	}
	wire, err := json.Marshal(lossless)
	if err != nil {
		panic(err)
	}
	fmt.Println(string(legacy))
	fmt.Println(string(wire))
	// Output:
	// "owner/plugin"
	// {"source":"unknown","metadata":true}
}

func TestSkillsPluginProvenanceAfterDecoding(t *testing.T) {
	for _, test := range []struct {
		source string
		want   string
	}{
		{source: `{"source":"github","repo":"owner/plugin","ref":"main"}`},
		{source: `{"source":"url","url":"https://example.com/plugin.git","ref":"main"}`},
		{source: `{"source":"local","path":"/plugins/local"}`},
		{source: `"marketplace:plugin"`},
		{source: `{"source":"unknown","repo":"owner/plugin","metadata":true}`},
		{source: `{"source":"github","metadata":true}`},
		{
			source: `{"source":"github","repo":"owner/plugin","ref":"main","metadata":{"origin":"catalog"},"url":"https://example.com/metadata"}`,
			want:   `{"source":"github","repo":"owner/plugin","ref":"main"}`,
		},
		{
			source: `{"source":"url","url":"https://example.com/plugin.git","metadata":{"origin":"catalog"},"repo":"catalog/metadata"}`,
			want:   `{"source":"url","url":"https://example.com/plugin.git"}`,
		},
		{
			source: `{"source":"local","path":"/plugins/local","metadata":{"origin":"catalog"},"repo":"catalog/metadata"}`,
			want:   `{"source":"local","path":"/plugins/local"}`,
		},
	} {
		source := test.source
		t.Run(source, func(t *testing.T) {
			var preview MCPDiscoverPluginServersRequest
			if err := json.Unmarshal([]byte(`{"installedPlugins":[{"name":"plugin","marketplace":"","enabled":true,"installed_at":"2026-11-18T00:00:00Z","source":`+source+`}]}`), &preview); err != nil {
				t.Fatal(err)
			}
			for _, request := range []struct {
				params any
				field  string
			}{
				{preview, "installedPlugins"},
				{SkillsLoadRequest{Cwd: "/work", AdditionalPlugins: preview.InstalledPlugins}, "additionalPlugins"},
			} {
				wire, err := json.Marshal(request.params)
				if err != nil {
					t.Fatal(err)
				}
				var plugins []struct {
					Source json.RawMessage `json:"source"`
				}
				var fields map[string]json.RawMessage
				if err := json.Unmarshal(wire, &fields); err != nil {
					t.Fatal(err)
				}
				if err := json.Unmarshal(fields[request.field], &plugins); err != nil {
					t.Fatal(err)
				}
				var got, want any
				if err := json.Unmarshal(plugins[0].Source, &got); err != nil {
					t.Fatal(err)
				}
				wantSource := test.want
				if wantSource == "" {
					wantSource = source
				}
				if err := json.Unmarshal([]byte(wantSource), &want); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(got, want) {
					t.Errorf("%s provenance = %s, want %s", request.field, plugins[0].Source, wantSource)
				}
			}
		})
	}
}

func TestSkillsSourceRequestArraysOnWire(t *testing.T) {
	for _, test := range []struct {
		name   string
		params any
		want   string
	}{
		{
			name: "load nil source lists",
			params: &SkillsLoadRequest{
				Cwd:    "/work",
				Inputs: &SkillScanInputs{CustomDirectories: []string{"/extra"}},
			},
			want: `{"cwd":"/work","inputs":{"commandSources":[],"customDirectories":["/extra"],"skillSources":[]}}`,
		},
		{
			name: "preview builtin without installed plugins",
			params: &MCPDiscoverPluginServersRequest{
				BuiltinPluginNames: []string{"bundled"},
			},
			want: `{"builtinPluginNames":["bundled"],"installedPlugins":[]}`,
		},
		{
			name:   "scan no plugin directories",
			params: &PluginsScanDirectoriesRequest{WorkingDirectory: "/work"},
			want:   `{"paths":[],"workingDirectory":"/work"}`,
		},
		{
			name: "install nil",
			params: &SkillsInstallSourceRequest{
				Cwd: "/work", PersonalSkillsDir: "/personal", Project: true,
				ProjectSkillsDir: "/project", Source: "https://example.com/skill.md", UserAgent: "test-agent",
			},
			want: `{"cwd":"/work","personalSkillsDir":"/personal","project":true,"projectSkillsDir":"/project","skillDirectories":[],"source":"https://example.com/skill.md","userAgent":"test-agent"}`,
		},
		{
			name:   "install empty",
			params: &SkillsInstallSourceRequest{SkillDirectories: []string{}},
			want:   `{"cwd":"","personalSkillsDir":"","project":false,"projectSkillsDir":"","skillDirectories":[],"source":"","userAgent":""}`,
		},
		{
			name:   "install populated",
			params: &SkillsInstallSourceRequest{SkillDirectories: []string{"/extra"}},
			want:   `{"cwd":"","personalSkillsDir":"","project":false,"projectSkillsDir":"","skillDirectories":["/extra"],"source":"","userAgent":""}`,
		},
		{
			name:   "remove nil without inventory",
			params: &SkillsRemoveSourceRequest{Cwd: "/work", Target: "/extra"},
			want:   `{"cwd":"/work","skillDirectories":[],"target":"/extra"}`,
		},
		{
			name:   "remove nil inventory lists",
			params: &SkillsRemoveSourceRequest{Cwd: "/work", Target: "skill", Inventory: &SkillsRemoveSourceInventory{ProjectSkillsDir: "/project"}},
			want:   `{"cwd":"/work","inventory":{"personalSkillsDirs":[],"projectSkillsDir":"/project","skills":[]},"skillDirectories":[],"target":"skill"}`,
		},
		{
			name: "remove empty",
			params: &SkillsRemoveSourceRequest{
				SkillDirectories: []string{},
				Inventory:        &SkillsRemoveSourceInventory{PersonalSkillsDirs: []string{}, Skills: []SkillRemoveSourceEntry{}},
			},
			want: `{"cwd":"","inventory":{"personalSkillsDirs":[],"projectSkillsDir":"","skills":[]},"skillDirectories":[],"target":""}`,
		},
		{
			name: "remove populated",
			params: &SkillsRemoveSourceRequest{
				Cwd: "/work", Target: "skill", SkillDirectories: []string{"/extra"},
				Inventory: &SkillsRemoveSourceInventory{
					PersonalSkillsDirs: []string{"/personal"}, ProjectSkillsDir: "/project",
					Skills: []SkillRemoveSourceEntry{{Name: "skill", BaseDir: "/personal/skill", Source: "personal"}},
				},
			},
			want: `{"cwd":"/work","inventory":{"personalSkillsDirs":["/personal"],"projectSkillsDir":"/project","skills":[{"name":"skill","baseDir":"/personal/skill","source":"personal"}]},"skillDirectories":["/extra"],"target":"skill"}`,
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			clientConn, serverConn := net.Pipe()
			defer clientConn.Close()
			defer serverConn.Close()
			client := jsonrpc2.NewClient(clientConn, clientConn)
			server := jsonrpc2.NewClient(serverConn, serverConn)
			requests := make(chan json.RawMessage, 1)
			method := "skills.installSource"
			response := `{"kind":"file","path":"/installed","skillDirectories":[]}`
			var original any
			switch params := test.params.(type) {
			case *SkillsLoadRequest:
				method = "skills.load"
				response = `{"skills":[],"warnings":[],"errors":[],"displayCwd":"/work","cacheGeneration":0,"sourcesChanged":false}`
				copy := *params
				inputs := *params.Inputs
				inputs.CustomDirectories = slices.Clone(inputs.CustomDirectories)
				inputs.SkillSources = slices.Clone(inputs.SkillSources)
				inputs.CommandSources = slices.Clone(inputs.CommandSources)
				copy.Inputs = &inputs
				original = &copy
			case *MCPDiscoverPluginServersRequest:
				method = "mcp.discoverPluginServers"
				response = `{"mcpServers":{}}`
				copy := *params
				copy.InstalledPlugins = slices.Clone(params.InstalledPlugins)
				copy.BuiltinPluginNames = slices.Clone(params.BuiltinPluginNames)
				original = &copy
			case *PluginsScanDirectoriesRequest:
				method = "plugins.scanDirectories"
				response = `{"plugins":[],"directories":[],"warnings":[]}`
				copy := *params
				copy.Paths = slices.Clone(params.Paths)
				original = &copy
			case *SkillsInstallSourceRequest:
				copy := *params
				copy.SkillDirectories = slices.Clone(params.SkillDirectories)
				original = &copy
			case *SkillsRemoveSourceRequest:
				method = "skills.removeSource"
				response = `{"kind":"needsInventory"}`
				copy := *params
				copy.SkillDirectories = slices.Clone(params.SkillDirectories)
				if params.Inventory != nil {
					inventory := *params.Inventory
					inventory.PersonalSkillsDirs = slices.Clone(inventory.PersonalSkillsDirs)
					inventory.Skills = slices.Clone(inventory.Skills)
					copy.Inventory = &inventory
				}
				original = &copy
			}
			server.SetRequestHandler(method, func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
				requests <- params
				return json.RawMessage(response), nil
			})
			client.Start()
			server.Start()
			defer client.Stop()
			defer server.Stop()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			api := NewServerRPC(client)
			var err error
			switch params := test.params.(type) {
			case *SkillsLoadRequest:
				_, err = api.Skills.Load(ctx, params)
			case *MCPDiscoverPluginServersRequest:
				_, err = api.MCP.DiscoverPluginServers(ctx, params)
			case *PluginsScanDirectoriesRequest:
				_, err = api.Plugins.ScanDirectories(ctx, params)
			case *SkillsInstallSourceRequest:
				_, err = api.Skills.InstallSource(ctx, params)
			case *SkillsRemoveSourceRequest:
				_, err = api.Skills.RemoveSource(ctx, params)
			}
			if err != nil {
				t.Fatal(err)
			}
			select {
			case raw := <-requests:
				var got, want any
				if err := json.Unmarshal(raw, &got); err != nil {
					t.Fatal(err)
				}
				if err := json.Unmarshal([]byte(test.want), &want); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(got, want) {
					t.Errorf("wire params = %s, want %s", raw, test.want)
				}
			case <-ctx.Done():
				t.Fatal(ctx.Err())
			}
			if !reflect.DeepEqual(test.params, original) {
				t.Errorf("caller options changed: got %#v, want %#v", test.params, original)
			}
		})
	}
}
