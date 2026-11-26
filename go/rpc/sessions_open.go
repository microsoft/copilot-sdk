// Copyright (c) Microsoft Corporation. All rights reserved.

package rpc

import (
	"context"
	"encoding/json"
	"fmt"
)

// SessionOpenRequestOptions extends sessions.open without changing positional request records.
// Use keyed literals for these extensible options.
type SessionOpenRequestOptions struct {
	// CallbackID correlates handoff progress and confirmation on this connection.
	// It is valid only for a SessionsOpenHandoff request.
	CallbackID *string
	// SessionLifecycleMode controls local end hooks at creation and cold resume.
	// Nil retains the request's lifecycle selection or the runtime default.
	// Resident and cloud sessions ignore this option. The mode is not persisted.
	SessionLifecycleMode *SessionLifecycleMode
}

// OpenWithOptions sends sessions.open with optional handoff correlation and hook lifetime.
// It preserves the original Open signature and positional request shapes.
func (a *ServerSessionsAPI) OpenWithOptions(ctx context.Context, params SessionOpenParams, options *SessionOpenRequestOptions) (*SessionOpenResult, error) {
	if options == nil || (options.CallbackID == nil && options.SessionLifecycleMode == nil) {
		return a.Open(ctx, params)
	}
	raw, err := json.Marshal(withEmptyRequiredListsSessionOpenParams(params))
	if err != nil {
		return nil, err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		return nil, err
	}
	if fields == nil {
		return nil, fmt.Errorf("sessions.open options require a non-null request")
	}
	if options.CallbackID != nil {
		if params.Kind() != SessionOpenParamsKindHandoff {
			return nil, fmt.Errorf("sessions.open callbackId requires a handoff request")
		}
		fields["callbackId"], err = json.Marshal(options.CallbackID)
		if err != nil {
			return nil, err
		}
	}
	if options.SessionLifecycleMode != nil {
		if params.Kind() == SessionOpenParamsKindAttach {
			return nil, fmt.Errorf("sessions.open lifecycle mode does not apply to attach requests")
		}
		nested := map[string]json.RawMessage{}
		if existing := fields["options"]; len(existing) != 0 && string(existing) != "null" {
			if err := json.Unmarshal(existing, &nested); err != nil {
				return nil, err
			}
		}
		nested["sessionLifecycleMode"], err = json.Marshal(options.SessionLifecycleMode)
		if err != nil {
			return nil, err
		}
		fields["options"], err = json.Marshal(nested)
		if err != nil {
			return nil, err
		}
	}
	raw, err = json.Marshal(fields)
	if err != nil {
		return nil, err
	}
	return a.Open(ctx, RawSessionOpenParamsData{Discriminator: params.Kind(), Raw: raw})
}
