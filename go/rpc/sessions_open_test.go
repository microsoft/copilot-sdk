// Copyright (c) Microsoft Corporation. All rights reserved.

package rpc

import (
	"context"
	"encoding/json"
	"io"
	"reflect"
	"strings"
	"testing"

	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
)

var _ interface {
	Open(context.Context, SessionOpenParams) (*SessionOpenResult, error)
} = (*ServerSessionsAPI)(nil)

func TestSessionsOpenLegacyPositionalWire(t *testing.T) {
	directory := "/workspace"
	options := SessionOpenOptions{
		nil, nil, nil, nil, nil, nil, nil, nil, nil, nil,
		nil, nil, nil, nil, nil, nil, nil, nil, nil, nil,
		nil, nil, nil, nil, nil, nil, nil, nil, nil, nil,
		nil, nil, nil, nil, &directory,
	}
	request := SessionsOpenHandoff{RemoteSessionMetadataValue{SessionID: "legacy"}, nil, nil, &options, nil}
	raw := captureParams(t, "sessions.open", func(ctx context.Context, srv *serverAPI, _ *sessionAPI) error {
		_, err := (*ServerSessionsAPI)(srv).Open(ctx, request)
		return err
	})
	var payload struct {
		Kind     string `json:"kind"`
		Metadata struct {
			SessionID        string   `json:"sessionId"`
			RemoteSessionIDs []string `json:"remoteSessionIds"`
		} `json:"metadata"`
		Options map[string]any `json:"options"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Kind != "handoff" || payload.Metadata.SessionID != "legacy" || payload.Metadata.RemoteSessionIDs == nil {
		t.Fatalf("legacy handoff or required-list normalization changed: %s", raw)
	}
	if !reflect.DeepEqual(payload.Options, map[string]any{"workingDirectory": directory}) {
		t.Fatalf("legacy positional options changed: %s", raw)
	}
	if request.Metadata.RemoteSessionIDs != nil {
		t.Fatal("request normalization changed caller-owned metadata")
	}
}

func TestSessionsOpenWithOptionsWire(t *testing.T) {
	callbackID := "handoff-1"
	mode := SessionLifecycleModeInteractive
	handoff := SessionsOpenHandoff{
		Metadata: RemoteSessionMetadataValue{SessionID: "legacy"},
		Options: &SessionOpenOptions{Policy: &SessionOpenPolicyOptions{
			AdditionalContentExclusionPolicies: []SessionOpenOptionsAdditionalContentExclusionPolicy{{}},
		}},
	}
	for _, testCase := range []struct {
		name    string
		params  SessionOpenParams
		options *SessionOpenRequestOptions
	}{
		{"handoff value", handoff, &SessionOpenRequestOptions{CallbackID: &callbackID, SessionLifecycleMode: &mode}},
		{"handoff pointer", &handoff, &SessionOpenRequestOptions{CallbackID: &callbackID, SessionLifecycleMode: &mode}},
		{"create without options", SessionsOpenCreate{}, &SessionOpenRequestOptions{SessionLifecycleMode: &mode}},
		{"resume", SessionsOpenResume{SessionID: "legacy"}, &SessionOpenRequestOptions{SessionLifecycleMode: &mode}},
		{"raw fields", RawSessionOpenParamsData{Discriminator: SessionOpenParamsKindCreate, Raw: json.RawMessage(`{"kind":"create","options":{"futureCount":9007199254740993}}`)}, &SessionOpenRequestOptions{SessionLifecycleMode: &mode}},
		{"nil extensions", handoff, nil},
		{"empty extensions", handoff, &SessionOpenRequestOptions{}},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			raw := captureParams(t, "sessions.open", func(ctx context.Context, srv *serverAPI, _ *sessionAPI) error {
				_, err := (*ServerSessionsAPI)(srv).OpenWithOptions(ctx, testCase.params, testCase.options)
				return err
			})
			var payload map[string]any
			if err := json.Unmarshal(raw, &payload); err != nil {
				t.Fatal(err)
			}
			if payload["kind"] != string(testCase.params.Kind()) {
				t.Fatalf("request discriminator changed: %s", raw)
			}
			nested, _ := payload["options"].(map[string]any)
			if testCase.options != nil && testCase.options.SessionLifecycleMode != nil {
				if nested["sessionLifecycleMode"] != string(mode) {
					t.Fatalf("lifecycle mode was lost: %s", raw)
				}
			} else if _, present := nested["sessionLifecycleMode"]; present {
				t.Fatalf("unset lifecycle mode was emitted: %s", raw)
			}
			if testCase.options != nil && testCase.options.CallbackID != nil {
				if payload["callbackId"] != callbackID {
					t.Fatalf("handoff callback correlation was lost: %s", raw)
				}
			} else if _, present := payload["callbackId"]; present {
				t.Fatalf("unset callback ID was emitted: %s", raw)
			}
			if testCase.params.Kind() == SessionOpenParamsKindHandoff {
				metadata := payload["metadata"].(map[string]any)
				if !reflect.DeepEqual(metadata["remoteSessionIds"], []any{}) {
					t.Fatalf("required metadata list was not normalized: %s", raw)
				}
				policy := nested["policy"].(map[string]any)
				rule := policy["additionalContentExclusionPolicies"].([]any)[0].(map[string]any)
				if !reflect.DeepEqual(rule["rules"], []any{}) {
					t.Fatalf("required policy list was not normalized: %s", raw)
				}
			}
			if testCase.name == "raw fields" && !strings.Contains(string(raw), "9007199254740993") {
				t.Fatalf("opaque JSON number changed: %s", raw)
			}
			if handoff.Metadata.RemoteSessionIDs != nil || handoff.Options.Policy.AdditionalContentExclusionPolicies[0].Rules != nil {
				t.Fatal("OpenWithOptions changed caller-owned required lists")
			}
			if callbackID != "handoff-1" || mode != SessionLifecycleModeInteractive {
				t.Fatal("OpenWithOptions changed caller-owned extensions")
			}
		})
	}
}

func TestSessionsOpenWithOptionsRejectsInvalidRequests(t *testing.T) {
	callbackID := "handoff-1"
	mode := SessionLifecycleModePerTurn
	for _, testCase := range []struct {
		name    string
		params  SessionOpenParams
		options SessionOpenRequestOptions
		want    string
	}{
		{"nil request", nil, SessionOpenRequestOptions{SessionLifecycleMode: &mode}, "non-null request"},
		{"typed nil request", (*SessionsOpenCreate)(nil), SessionOpenRequestOptions{SessionLifecycleMode: &mode}, "non-null request"},
		{"callback on create", SessionsOpenCreate{}, SessionOpenRequestOptions{CallbackID: &callbackID}, "handoff request"},
		{"lifecycle on attach", SessionsOpenAttach{SessionID: "legacy"}, SessionOpenRequestOptions{SessionLifecycleMode: &mode}, "attach requests"},
		{"encoding failure", SessionsOpenHandoff{OnConfirm: func() {}}, SessionOpenRequestOptions{CallbackID: &callbackID}, "unsupported type"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			api := &ServerSessionsAPI{}
			_, err := api.OpenWithOptions(t.Context(), testCase.params, &testCase.options)
			if err == nil || !strings.Contains(err.Error(), testCase.want) {
				t.Fatalf("OpenWithOptions error = %v, want %q", err, testCase.want)
			}
		})
	}
}

func TestSessionsOpenPreservesUnionValuesAndWire(t *testing.T) {
	emit := false
	suppress := true
	sessionID := "legacy"
	var inference SessionOpenInferenceOptions
	if err := json.Unmarshal([]byte(`{"defaultProvider":"gateway","providers":[{"name":"gateway","baseUrl":"https://provider.invalid"}],"models":[{"provider":"gateway","id":"friendly","wireModel":"deployment"}]}`), &inference); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(`{"reasoningEffort":"high"}`), &inference); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		params SessionOpenParams
		want   map[string]any
	}{
		{
			SessionsOpenCreate{EmitStart: &emit},
			map[string]any{"kind": "create", "emitStart": false},
		},
		{
			SessionsOpenResume{Resume: &emit, SessionID: sessionID, SuppressResumeWorkspaceMetadataWriteback: &suppress},
			map[string]any{
				"kind": "resume", "resume": false, "sessionId": sessionID,
				"suppressResumeWorkspaceMetadataWriteback": true,
			},
		},
		{nil, nil},
		{
			SessionsOpenCreate{Options: &SessionOpenOptions{Inference: &inference}},
			map[string]any{
				"kind": "create",
				"options": map[string]any{"inference": map[string]any{
					"defaultProvider": "gateway",
					"providers":       []any{map[string]any{"name": "gateway", "baseUrl": "https://provider.invalid"}},
					"models":          []any{map[string]any{"provider": "gateway", "id": "friendly", "wireModel": "deployment"}},
					"reasoningEffort": "high",
				}},
			},
		},
	}
	expected := SessionOpenResult{
		SessionAPI:     map[string]any{"legacy": true},
		SessionID:      &sessionID,
		StartupPrompts: []string{"startup"},
		Status:         SessionsOpenStatusCreated,
	}

	clientReader, clientWriter := io.Pipe()
	serverReader, serverWriter := io.Pipe()
	client := jsonrpc2.NewClient(clientWriter, serverReader)
	server := jsonrpc2.NewClient(serverWriter, clientReader)
	requests := make(chan map[string]any, len(cases))
	server.SetRequestHandler("sessions.open", func(params json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
		var request map[string]any
		if err := json.Unmarshal(params, &request); err != nil {
			return nil, &jsonrpc2.Error{Code: -32602, Message: err.Error()}
		}
		requests <- request
		return json.RawMessage(`{"sessionId":"legacy","startupPrompts":["startup"],"status":"created","sessionApi":{"legacy":true},"nativeConstructionPlan":{"host":"private"}}`), nil
	})
	client.Start()
	server.Start()
	t.Cleanup(func() {
		client.Stop()
		server.Stop()
		_ = clientWriter.Close()
		_ = clientReader.Close()
		_ = serverWriter.Close()
		_ = serverReader.Close()
	})

	sessions := NewServerRPC(client).Sessions
	for _, testCase := range cases {
		result, err := sessions.Open(t.Context(), testCase.params)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(result, &expected) {
			t.Fatalf("open result = %#v, want %#v", result, &expected)
		}
		if actual := <-requests; !reflect.DeepEqual(actual, testCase.want) {
			t.Fatalf("open request = %#v, want %#v", actual, testCase.want)
		}
		encoded, err := json.Marshal(result)
		if err != nil {
			t.Fatal(err)
		}
		var publicResult map[string]any
		if err := json.Unmarshal(encoded, &publicResult); err != nil {
			t.Fatal(err)
		}
		if plan, exists := publicResult["nativeConstructionPlan"]; exists {
			t.Fatalf("private host plan reached the public result: %#v", plan)
		}
	}
	if err := json.Unmarshal([]byte(`{"defaultProvider":null,"models":[]}`), &inference); err != nil {
		t.Fatal(err)
	}
	if inference.DefaultProvider == nil || *inference.DefaultProvider != nil {
		t.Fatalf("explicit null did not clear the default provider: %#v", inference.DefaultProvider)
	}
	if inference.Models == nil || len(inference.Models) != 0 || len(inference.Providers) != 1 || inference.ReasoningEffort == nil || *inference.ReasoningEffort != "high" {
		t.Fatalf("partial clear changed omitted inference fields: %#v", inference)
	}
}
