/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package e2e

import (
	"net/http"
	"slices"
	"strings"
	"sync"
	"testing"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
)

// Fake BYOK provider base URLs. These hosts are never actually dialed: the
// capturing RoundTripper fully answers any request aimed at a `.invalid` host,
// so they only need to be syntactically valid, non-resolving URLs. Distinct
// hosts let the per-provider test assert routing by host.
const (
	byokRedHost     = "byok-red.invalid"
	byokRedBaseURL  = "https://" + byokRedHost + "/v1"
	byokBlueHost    = "byok-blue.invalid"
	byokBlueBaseURL = "https://" + byokBlueHost + "/v1"
)

// capturedBYOKRequest records the host and Authorization header of one outbound
// HTTP request the runtime aimed at a fake BYOK provider endpoint.
type capturedBYOKRequest struct {
	host          string
	authorization string
}

// byokCapturingRoundTripper stands in for a real HTTP upstream. It records the
// `Authorization` header the runtime applied (after calling the provider's
// BearerTokenProvider callback over the session-scoped `providerToken.getToken` RPC)
// for every request aimed at a fake `.invalid` BYOK host, answering them with a
// synthetic 404 (a non-retryable status, so each outbound model request yields
// exactly one capture). Every other request (CAPI bootstrap: model catalog,
// policy, session) is fabricated locally so the test never touches the network.
type byokCapturingRoundTripper struct {
	mu       sync.Mutex
	captures []capturedBYOKRequest
}

func (rt *byokCapturingRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	if strings.HasSuffix(req.URL.Hostname(), ".invalid") {
		rt.mu.Lock()
		rt.captures = append(rt.captures, capturedBYOKRequest{
			host:          req.URL.Host,
			authorization: req.Header.Get("Authorization"),
		})
		rt.mu.Unlock()
		if req.Body != nil {
			_ = req.Body.Close()
		}
		return buildJSONResponse(http.StatusNotFound, `{"error":{"message":"fake byok endpoint"}}`), nil
	}
	return buildNonInferenceResponse(req.URL.String()), nil
}

// authHeaderForHost returns the Authorization header captured for requests aimed
// at host, if any.
func (rt *byokCapturingRoundTripper) authHeaderForHost(host string) string {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	for _, c := range rt.captures {
		if c.host == host {
			return c.authorization
		}
	}
	return ""
}

func (rt *byokCapturingRoundTripper) reset() {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	rt.captures = nil
}

// TestBYOKBearerTokenProvider is end-to-end coverage for the experimental BYOK
// bearer-token-provider surface (BearerTokenProvider on a provider config). The
// callback stays entirely on the SDK/client side: the SDK strips it from the
// wire config, sets the `hasBearerTokenProvider` flag, and the runtime calls
// back over the session-scoped `providerToken.getToken` RPC before each outbound
// model request, applying the returned token as the `Authorization` header.
//
// Rather than standing up a real HTTP listener, the test installs a capturing
// RoundTripper that intercepts the runtime's outbound model request in-process,
// captures the `Authorization` header, and returns a synthetic response. It
// validates the Go callback binding against a real runtime: per-provider
// dispatch routes each provider's turn to its own callback with the forwarded
// provider name and session id, and the resulting token reaches that
// provider's endpoint. Runtime per-request re-acquisition is owned by the
// Node.js E2E.
func TestBYOKBearerTokenProvider(t *testing.T) {
	t.Parallel()
	testharness.SkipIfInProcess(t, "an LLM inference provider is process-global in-process")
	ctx := testharness.NewTestContext(t)
	rt := &byokCapturingRoundTripper{}
	handler := &copilot.CopilotRequestHandler{Transport: rt}

	client := newCopilotRequestClient(ctx, handler)
	t.Cleanup(func() { client.ForceStop() })
	if err := client.Start(t.Context()); err != nil {
		t.Fatalf("Failed to start client: %v", err)
	}

	// runTurn drives one BYOK turn; the synthetic 404 errors the turn after the
	// runtime has already sent the token-bearing request, which is all the test
	// asserts on, so the resulting error is expected and swallowed.
	runTurn := func(providers []copilot.NamedProviderConfig, models []copilot.ProviderModelConfig, selectionID, prompt string) {
		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Model: selectionID, Inference: copilot.SessionInferenceOptions{Providers: providers, Models: models}})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}
		_, _ = session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: prompt})
		_ = session.Disconnect()
	}

	t.Run("dispatches token acquisition per provider", func(t *testing.T) {
		rt.reset()
		tokenByProvider := map[string]string{
			"red":  "token-for-red",
			"blue": "token-for-blue",
		}
		var mu sync.Mutex
		var acquiredFor []string
		makeCallback := func(providerName string) copilot.BearerTokenProvider {
			return func(args copilot.ProviderTokenArgs) (string, error) {
				// The runtime forwards the requesting provider's name so the
				// client can dispatch to the right credential.
				if args.ProviderName != providerName {
					t.Errorf("Expected providerName %q, got %q", providerName, args.ProviderName)
				}
				// The runtime also forwards the owning session id so a
				// client-level shared callback can resolve the session.
				if args.SessionID == "" {
					t.Errorf("Expected a non-empty session id in token args")
				}
				mu.Lock()
				acquiredFor = append(acquiredFor, providerName)
				mu.Unlock()
				return tokenByProvider[providerName], nil
			}
		}

		providers := []copilot.NamedProviderConfig{
			{
				Name:                "red",
				Type:                "openai",
				WireAPI:             "completions",
				BaseURL:             byokRedBaseURL,
				BearerTokenProvider: makeCallback("red"),
			},
			{
				Name:                "blue",
				Type:                "openai",
				WireAPI:             "completions",
				BaseURL:             byokBlueBaseURL,
				BearerTokenProvider: makeCallback("blue"),
			},
		}
		models := []copilot.ProviderModelConfig{
			{ID: "default", Provider: "red", WireModel: "byok-gpt-4o"},
			{ID: "default", Provider: "blue", WireModel: "byok-gpt-4o"},
		}

		runTurn(providers, models, "red/default", "What is 3+3?")
		runTurn(providers, models, "blue/default", "What is 4+4?")

		// Each provider's turn was authenticated with its own token AND that
		// token was delivered to that provider's endpoint, proving per-provider
		// dispatch (not a single session-global credential).
		if got := rt.authHeaderForHost(byokRedHost); got != "Bearer "+tokenByProvider["red"] {
			t.Fatalf("Expected red host to receive %q, got %q", "Bearer "+tokenByProvider["red"], got)
		}
		if got := rt.authHeaderForHost(byokBlueHost); got != "Bearer "+tokenByProvider["blue"] {
			t.Fatalf("Expected blue host to receive %q, got %q", "Bearer "+tokenByProvider["blue"], got)
		}
		mu.Lock()
		got := append([]string(nil), acquiredFor...)
		mu.Unlock()
		if !slices.Contains(got, "red") || !slices.Contains(got, "blue") {
			t.Fatalf("Expected both providers to acquire tokens, got %v", got)
		}
	})
}
