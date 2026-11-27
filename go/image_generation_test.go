// Copyright (c) Microsoft Corporation. All rights reserved.

package copilot

import (
	"encoding/json"
	"reflect"
	"testing"
)

func TestImageGenerationSerialization(t *testing.T) {
	for _, enabled := range []*bool{Bool(true), Bool(false), nil} {
		var config *ImageGenerationConfig
		if enabled != nil {
			config = &ImageGenerationConfig{Enabled: enabled}
		}

		for _, request := range []any{
			createSessionRequest{ImageGeneration: config},
			resumeSessionRequest{SessionID: "image-test", ImageGeneration: config},
		} {
			data, err := json.Marshal(request)
			if err != nil {
				t.Fatal(err)
			}
			var payload map[string]json.RawMessage
			if err := json.Unmarshal(data, &payload); err != nil {
				t.Fatal(err)
			}
			value, present := sessionWireGroup(payload, "tools")["imageGeneration"]
			if enabled == nil {
				if present {
					t.Fatalf("expected omitted imageGeneration, got %s", data)
				}
			} else {
				expected, err := json.Marshal(map[string]bool{"enabled": *enabled})
				if err != nil {
					t.Fatal(err)
				}
				if string(value) != string(expected) {
					t.Fatalf("imageGeneration = %s, want %s", value, expected)
				}
			}
		}
	}
	data, err := json.Marshal(ImageGenerationConfig{})
	if err != nil || string(data) != "{}" {
		t.Fatalf("unset enabled = %s, err = %v", data, err)
	}
}

func TestImageOptionsSerializeWithoutEnablingImages(t *testing.T) {
	for _, watermark := range []bool{true, false} {
		saveToDisk := !watermark
		config := &ImageGenerationConfig{Watermark: Bool(watermark), SaveToDisk: Bool(saveToDisk)}
		for _, request := range []any{
			createSessionRequest{ImageGeneration: config},
			resumeSessionRequest{SessionID: "image-test", ImageGeneration: config},
		} {
			data, err := json.Marshal(request)
			if err != nil {
				t.Fatal(err)
			}
			var payload struct {
				Tools struct {
					ImageGeneration map[string]bool `json:"imageGeneration"`
				} `json:"tools"`
			}
			if err := json.Unmarshal(data, &payload); err != nil {
				t.Fatal(err)
			}
			want := map[string]bool{"watermark": watermark, "saveToDisk": saveToDisk}
			if !reflect.DeepEqual(payload.Tools.ImageGeneration, want) {
				t.Fatalf("image options must serialize without enabling images: %s", data)
			}
		}
	}
}
