// Copyright (c) Microsoft Corporation. All rights reserved.

package rpc

import (
	"errors"
	"fmt"
)

// ToInstalledPlugin converts a scan or preview record to the legacy plugin type.
// It rejects either source when Raw is set, because the legacy type has no raw field.
// It does not modify plugin. The result shares typed member pointers with plugin.
func ToInstalledPlugin(plugin InstalledPluginWithRawSource) (InstalledPlugin, error) {
	source, err := toInstalledPluginSource(plugin.Source)
	if err != nil {
		return InstalledPlugin{}, fmt.Errorf("source: %w", err)
	}
	installedSource, err := toInstalledPluginSource(plugin.InstalledSource)
	if err != nil {
		return InstalledPlugin{}, fmt.Errorf("installed_source: %w", err)
	}
	return InstalledPlugin{
		CachePath:       plugin.CachePath,
		Enabled:         plugin.Enabled,
		InstalledAt:     plugin.InstalledAt,
		InstalledFrom:   plugin.InstalledFrom,
		InstalledSource: installedSource,
		Marketplace:     plugin.Marketplace,
		Name:            plugin.Name,
		Source:          source,
		SourceSha:       plugin.SourceSha,
		Version:         plugin.Version,
	}, nil
}

// ToInstalledPluginWithRawSource converts a legacy plugin to a scan or preview record.
// It does not modify plugin. The result shares typed member pointers with plugin.
func ToInstalledPluginWithRawSource(plugin InstalledPlugin) InstalledPluginWithRawSource {
	return InstalledPluginWithRawSource{
		CachePath:       plugin.CachePath,
		Enabled:         plugin.Enabled,
		InstalledAt:     plugin.InstalledAt,
		InstalledFrom:   plugin.InstalledFrom,
		InstalledSource: toInstalledPluginSourceWithRaw(plugin.InstalledSource),
		Marketplace:     plugin.Marketplace,
		Name:            plugin.Name,
		Source:          toInstalledPluginSourceWithRaw(plugin.Source),
		SourceSha:       plugin.SourceSha,
		Version:         plugin.Version,
	}
}

// ToInstalledPlugins converts scan or preview records for legacy plugin requests.
// On failure, it returns nil and an error with the plugin index and source field.
// It preserves nil and empty slices. It does not modify plugins.
func ToInstalledPlugins(plugins []InstalledPluginWithRawSource) ([]InstalledPlugin, error) {
	if plugins == nil {
		return nil, nil
	}
	result := make([]InstalledPlugin, len(plugins))
	for i, plugin := range plugins {
		converted, err := ToInstalledPlugin(plugin)
		if err != nil {
			return nil, fmt.Errorf("plugins[%d]: %w", i, err)
		}
		result[i] = converted
	}
	return result, nil
}

// ToInstalledPluginsWithRawSource converts legacy plugin records for preview requests.
// It preserves nil and empty slices. It does not modify plugins.
func ToInstalledPluginsWithRawSource(plugins []InstalledPlugin) []InstalledPluginWithRawSource {
	if plugins == nil {
		return nil
	}
	result := make([]InstalledPluginWithRawSource, len(plugins))
	for i, plugin := range plugins {
		result[i] = ToInstalledPluginWithRawSource(plugin)
	}
	return result
}

func toInstalledPluginSource(source *InstalledPluginSourceWithRaw) (*InstalledPluginSource, error) {
	if source == nil {
		return nil, nil
	}
	if source.Raw != nil {
		return nil, errors.New("raw JSON cannot be represented by InstalledPluginSource")
	}
	return &InstalledPluginSource{
		InstalledPluginSourceGitHub: source.InstalledPluginSourceGitHub,
		InstalledPluginSourceLocal:  source.InstalledPluginSourceLocal,
		InstalledPluginSourceURL:    source.InstalledPluginSourceURL,
		String:                      source.String,
	}, nil
}

func toInstalledPluginSourceWithRaw(source *InstalledPluginSource) *InstalledPluginSourceWithRaw {
	if source == nil {
		return nil
	}
	return &InstalledPluginSourceWithRaw{
		InstalledPluginSourceGitHub: source.InstalledPluginSourceGitHub,
		InstalledPluginSourceLocal:  source.InstalledPluginSourceLocal,
		InstalledPluginSourceURL:    source.InstalledPluginSourceURL,
		String:                      source.String,
	}
}
