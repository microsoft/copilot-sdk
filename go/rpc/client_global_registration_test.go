// Copyright (c) GitHub. All rights reserved.

package rpc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"testing"
	"time"

	"github.com/microsoft/copilot-sdk/go/v2/internal/jsonrpc2"
)

type runtimeHostCallbacks struct {
	RuntimeHostHandler
	foreground func(*HostForegroundSessionRequest) (*RuntimeHostForegroundSessionChangeResult, error)
}

func (h runtimeHostCallbacks) ForegroundSessionChange(request *HostForegroundSessionRequest) (*RuntimeHostForegroundSessionChangeResult, error) {
	return h.foreground(request)
}

type handoffCallbacks struct {
	confirm  func(*SessionHandoffConfirmationRequest) (*SessionHandoffConfirmResult, error)
	progress func(*SessionHandoffProgressNotification) error
}

func (h handoffCallbacks) Confirm(request *SessionHandoffConfirmationRequest) (*SessionHandoffConfirmResult, error) {
	return h.confirm(request)
}

func (h handoffCallbacks) Progress(request *SessionHandoffProgressNotification) error {
	return h.progress(request)
}

func ExampleRegisterRuntimeHostHandler() {
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()
	client := jsonrpc2.NewClient(clientConn, clientConn)
	runtime := jsonrpc2.NewClient(serverConn, serverConn)
	progress := make(chan string, 1)
	RegisterClientGlobalAPIHandlers(client, &ClientGlobalAPIHandlers{
		ExtensionLaunchProvider: extensionLaunchProviderFunc(func(request *ExtensionLaunchProviderResolveRequest) (*ExtensionLaunchProviderResolveResult, error) {
			return &ExtensionLaunchProviderResolveResult{Launch: &ExtensionLaunchProfile{Executable: request.ID}}, nil
		}),
	})
	RegisterRuntimeHostHandler(client, runtimeHostCallbacks{foreground: func(request *HostForegroundSessionRequest) (*RuntimeHostForegroundSessionChangeResult, error) {
		return &RuntimeHostForegroundSessionChangeResult{Success: request.SessionID != nil && *request.SessionID == "selected-session"}, nil
	}})
	RegisterSessionHandoffHandler(client, handoffCallbacks{
		confirm: func(request *SessionHandoffConfirmationRequest) (*SessionHandoffConfirmResult, error) {
			return &SessionHandoffConfirmResult{Confirmed: request.CallbackID == "handoff-confirm"}, nil
		},
		progress: func(request *SessionHandoffProgressNotification) error {
			progress <- request.CallbackID
			return nil
		},
	})
	client.Start()
	runtime.Start()
	defer client.Stop()
	defer runtime.Stop()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	for _, test := range []struct {
		method string
		params any
	}{
		{"extensionLaunchProvider.resolve", map[string]any{"id": "/legacy/launcher"}},
		{"runtimeHost.foregroundSessionChange", map[string]any{"sessionId": "selected-session"}},
		{"sessionHandoff.confirm", map[string]any{"callbackId": "handoff-confirm"}},
	} {
		raw, err := runtime.Request(ctx, test.method, test.params)
		if err != nil {
			panic(err)
		}
		fmt.Printf("%s: %s\n", test.method, raw)
	}

	notification := `{"jsonrpc":"2.0","method":"sessionHandoff.progress","params":{"callbackId":"handoff-progress"}}`
	if _, err := fmt.Fprintf(serverConn, "Content-Length: %d\r\n\r\n%s", len(notification), notification); err != nil {
		panic(err)
	}
	select {
	case got := <-progress:
		fmt.Println("progress:", got)
	case <-ctx.Done():
		panic(ctx.Err())
	}
	// Output:
	// extensionLaunchProvider.resolve: {"launch":{"args":[],"env":{},"executable":"/legacy/launcher"}}
	// runtimeHost.foregroundSessionChange: {"success":true}
	// sessionHandoff.confirm: {"confirmed":true}
	// progress: handoff-progress
}

func TestClientGlobalAdditionalRegistrationErrors(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()
	client := jsonrpc2.NewClient(clientConn, clientConn)
	runtime := jsonrpc2.NewClient(serverConn, serverConn)
	RegisterClientGlobalAPIHandlers(client, nil)
	RegisterRuntimeHostHandler(client, nil)
	RegisterSessionHandoffHandler(client, nil)
	client.SetRequestHandler("test.barrier", func(json.RawMessage) (json.RawMessage, *jsonrpc2.Error) {
		return json.RawMessage(`{}`), nil
	})
	client.Start()
	runtime.Start()
	defer client.Stop()
	defer runtime.Stop()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	for _, method := range []string{"extensionLaunchProvider.resolve", "runtimeHost.foregroundSessionChange", "sessionHandoff.confirm"} {
		_, err := runtime.Request(ctx, method, map[string]any{})
		var rpcErr *jsonrpc2.Error
		if !errors.As(err, &rpcErr) || rpcErr.Code != -32603 {
			t.Fatalf("%s missing handler error = %v, want -32603", method, err)
		}
	}
	notification := `{"jsonrpc":"2.0","method":"sessionHandoff.progress","params":{}}`
	if _, err := fmt.Fprintf(serverConn, "Content-Length: %d\r\n\r\n%s", len(notification), notification); err != nil {
		t.Fatal(err)
	}
	// Notifications finish on the read loop before the next request can run.
	if _, err := runtime.Request(ctx, "test.barrier", map[string]any{}); err != nil {
		t.Fatalf("request after nil notification handler: %v", err)
	}
	if _, err := runtime.Request(ctx, "sessionHandoff.confirm", "invalid object"); err == nil {
		t.Fatal("invalid params succeeded")
	} else {
		var rpcErr *jsonrpc2.Error
		if !errors.As(err, &rpcErr) || rpcErr.Code != -32602 {
			t.Fatalf("invalid params error = %v, want -32602", err)
		}
	}

	for _, test := range []struct {
		err  error
		code int
	}{
		{fmt.Errorf("wrapped: %w", &jsonrpc2.Error{Code: -32042, Message: "denied"}), -32042},
		{errors.New("callback failed"), -32603},
	} {
		RegisterSessionHandoffHandler(client, handoffCallbacks{
			confirm: func(*SessionHandoffConfirmationRequest) (*SessionHandoffConfirmResult, error) {
				return nil, test.err
			},
		})
		_, err := runtime.Request(ctx, "sessionHandoff.confirm", map[string]any{})
		var rpcErr *jsonrpc2.Error
		if !errors.As(err, &rpcErr) || rpcErr.Code != test.code {
			t.Fatalf("callback error = %v, want %d", err, test.code)
		}
	}
}
