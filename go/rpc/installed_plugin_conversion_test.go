// Copyright (c) Microsoft Corporation. All rights reserved.

package rpc_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"reflect"
	"testing"

	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func TestInstalledPluginConversionsPreserveRequests(t *testing.T) {
	for _, test := range []struct {
		name   string
		source string
	}{
		{"absent", ""},
		{"empty union", "null"},
		{"string", `"owner/plugin"`},
		{"github", `{"source":"github","repo":"owner/plugin","path":"plugin","ref":"main","sha":"revision"}`},
		{"local", `{"source":"local","path":"/plugins/example"}`},
		{"url", `{"source":"url","url":"https://example.com/plugin","path":"plugin","ref":"main","sha":"revision"}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			var source *rpc.InstalledPluginSource
			if test.source != "" {
				source = new(rpc.InstalledPluginSource)
				if err := json.Unmarshal([]byte(test.source), source); err != nil {
					t.Fatal(err)
				}
			}
			var installedSource *rpc.InstalledPluginSource
			if test.name != "absent" {
				slug := "catalog/original"
				installedSource = &rpc.InstalledPluginSource{String: &slug}
			}
			cache, from, sha, version := "/cache/example", "/plugins", "fingerprint", "1.2.3"
			original := rpc.InstalledPlugin{
				CachePath: &cache, Enabled: true, InstalledAt: "2026-11-25T00:00:00Z",
				InstalledFrom: &from, InstalledSource: installedSource, Marketplace: "catalog",
				Name: "example", Source: source, SourceSha: &sha, Version: &version,
			}
			before, err := json.Marshal([]rpc.InstalledPlugin{original})
			if err != nil {
				t.Fatal(err)
			}
			projected := rpc.ToInstalledPluginWithRawSource(original)
			converted, err := rpc.ToInstalledPlugin(projected)
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(converted, original) {
				t.Fatalf("converted plugin = %#v; want %#v", converted, original)
			}
			if (projected.Source == nil) != (source == nil) ||
				(projected.InstalledSource == nil) != (installedSource == nil) {
				t.Fatal("conversion changed an absent source into a present source, or the reverse")
			}

			activation := rpc.UIPluginActivationSnapshot{Plugins: []rpc.InstalledPlugin{original}}
			preview := rpc.MCPDiscoverPluginServersRequest{
				InstalledPlugins: rpc.ToInstalledPluginsWithRawSource(activation.Plugins),
			}
			skills := rpc.SkillsLoadRequest{AdditionalPlugins: preview.InstalledPlugins, Cwd: "/workspace"}
			scan := rpc.PluginsScanDirectoriesResult{Plugins: skills.AdditionalPlugins}
			plugins, err := rpc.ToInstalledPlugins(scan.Plugins)
			if err != nil {
				t.Fatal(err)
			}
			update := rpc.SessionsSetAdditionalPluginsRequest{Plugins: plugins}
			for name, test := range map[string]struct {
				value any
				want  string
			}{
				"activation": {activation.Plugins, string(before)},
				"single":     {[]rpc.InstalledPluginWithRawSource{projected}, string(before)},
				"preview":    {preview, `{"installedPlugins":` + string(before) + `}`},
				"skills":     {skills, `{"additionalPlugins":` + string(before) + `,"cwd":"/workspace"}`},
				"update":     {update, `{"plugins":` + string(before) + `}`},
			} {
				wire, err := json.Marshal(test.value)
				if err != nil {
					t.Fatal(err)
				}
				if string(wire) != test.want {
					t.Errorf("%s wire = %s; want %s", name, wire, test.want)
				}
			}
		})
	}
}

func TestInstalledPluginConversionsRejectRawWithoutMutation(t *testing.T) {
	for _, test := range []struct {
		field string
		raw   string
	}{
		{"source", `{"source":"future","metadata":{"origin":"catalog"}}`},
		{"installed_source", `{"source":"github","metadata":{"origin":"catalog"}}`},
	} {
		t.Run(test.field, func(t *testing.T) {
			var plugin rpc.InstalledPluginWithRawSource
			if err := json.Unmarshal([]byte(`{"name":"future","`+test.field+`":`+test.raw+`}`), &plugin); err != nil {
				t.Fatal(err)
			}
			plugins := []rpc.InstalledPluginWithRawSource{{Name: "compatible"}, plugin}
			before, err := json.Marshal(plugins)
			if err != nil {
				t.Fatal(err)
			}
			converted, err := rpc.ToInstalledPlugin(plugin)
			want := test.field + ": raw JSON cannot be represented by InstalledPluginSource"
			if err == nil || err.Error() != want || !reflect.DeepEqual(converted, rpc.InstalledPlugin{}) {
				t.Fatalf("conversion = %#v, %v; want zero value, %q", converted, err, want)
			}
			result, err := rpc.ToInstalledPlugins(plugins)
			want = "plugins[1]: " + want
			if err == nil || err.Error() != want || result != nil {
				t.Fatalf("slice conversion = %#v, %v; want nil, %q", result, err, want)
			}
			after, err := json.Marshal(plugins)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(after, before) {
				t.Fatalf("input changed to %s; was %s", after, before)
			}
		})
	}
}

func TestInstalledPluginConversionsPreserveEmptySlices(t *testing.T) {
	for _, plugins := range [][]rpc.InstalledPlugin{nil, {}} {
		projected := rpc.ToInstalledPluginsWithRawSource(plugins)
		converted, err := rpc.ToInstalledPlugins(projected)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(converted, plugins) || (projected == nil) != (plugins == nil) {
			t.Fatalf("conversion = %#v -> %#v -> %#v; nil and empty must stay distinct", plugins, projected, converted)
		}
	}
}

func ExampleToInstalledPlugins() {
	var scan rpc.PluginsScanDirectoriesResult
	if err := json.Unmarshal([]byte(`{"plugins":[{"name":"example","source":"owner/plugin"}]}`), &scan); err != nil {
		fmt.Println(err)
		return
	}
	plugins, err := rpc.ToInstalledPlugins(scan.Plugins)
	if err != nil {
		fmt.Println(err)
		return
	}
	update := rpc.SessionsSetAdditionalPluginsRequest{Plugins: plugins}
	activation := rpc.UIPluginActivationSnapshot{Plugins: update.Plugins}
	preview := rpc.MCPDiscoverPluginServersRequest{
		InstalledPlugins: rpc.ToInstalledPluginsWithRawSource(activation.Plugins),
	}
	skills := rpc.SkillsLoadRequest{AdditionalPlugins: preview.InstalledPlugins, Cwd: "/workspace"}
	fmt.Println(update.Plugins[0].Name, preview.InstalledPlugins[0].Name, skills.AdditionalPlugins[0].Name)

	scan.Plugins = append(scan.Plugins, rpc.InstalledPluginWithRawSource{
		Name: "future",
		Source: &rpc.InstalledPluginSourceWithRaw{
			Raw: json.RawMessage(`{"source":"future","metadata":{"origin":"catalog"}}`),
		},
	})
	_, err = rpc.ToInstalledPlugins(scan.Plugins)
	fmt.Println(err)
	// Output:
	// example example example
	// plugins[1]: source: raw JSON cannot be represented by InstalledPluginSource
}
