package e2e

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
)

// hasImageURLContent returns true if any user message in the given exchanges
// contains an image_url content part (multimodal vision content).
func hasImageURLContent(exchanges []testharness.ParsedHttpExchange) bool {
	for _, ex := range exchanges {
		for _, msg := range ex.Request.Messages {
			if msg.Role == "user" && len(msg.RawContent) > 0 {
				var content []interface{}
				if json.Unmarshal(msg.RawContent, &content) == nil {
					for _, part := range content {
						if m, ok := part.(map[string]interface{}); ok {
							if m["type"] == "image_url" {
								return true
							}
						}
					}
				}
			}
		}
	}
	return false
}

func createPDFAttachment() copilot.Attachment {
	pdfText := "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
	data := base64.StdEncoding.EncodeToString([]byte(pdfText))
	displayName := "citation-source.pdf"
	return copilot.AttachmentBlob{
		Data:        &data,
		DisplayName: &displayName,
		MIMEType:    "application/pdf",
	}
}

func createAnthropicProvider() *copilot.ProviderConfig {
	return &copilot.ProviderConfig{
		Type:      "anthropic",
		BaseURL:   "https://anthropic-citations.invalid/v1",
		APIKey:    "test-provider-key",
		ModelID:   "claude-sonnet-5",
		WireModel: "claude-sonnet-5",
	}
}

func assertAnthropicDocumentCitationsEnabled(t *testing.T, requestBody string) {
	t.Helper()

	var body struct {
		Messages []struct {
			Content []map[string]any `json:"content"`
		} `json:"messages"`
	}
	if err := json.Unmarshal([]byte(requestBody), &body); err != nil {
		t.Fatalf("Failed to unmarshal Anthropic request body: %v", err)
	}
	var documents []map[string]any
	for _, message := range body.Messages {
		for _, block := range message.Content {
			if block["type"] == "document" {
				documents = append(documents, block)
			}
		}
	}
	if len(documents) != 1 {
		t.Fatalf("Expected one Anthropic document block, got %d in body %s", len(documents), requestBody)
	}
	if documents[0]["title"] != "citation-source.pdf" {
		t.Fatalf("Expected document title citation-source.pdf, got %v", documents[0]["title"])
	}
	citations, ok := documents[0]["citations"].(map[string]any)
	if !ok || citations["enabled"] != true {
		t.Fatalf("Expected document citations.enabled=true, got %#v", documents[0]["citations"])
	}
}

func TestSessionConfigE2E(t *testing.T) {
	t.Parallel()
	ctx := testharness.NewTestContext(t)
	client := ctx.NewClient()
	t.Cleanup(func() { client.ForceStop() })

	if err := client.Start(t.Context()); err != nil {
		t.Fatalf("Failed to start client: %v", err)
	}

	// Write 1x1 PNG to the work directory
	png1x1, err := base64.StdEncoding.DecodeString("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
	if err != nil {
		t.Fatalf("Failed to decode PNG: %v", err)
	}
	if err := os.WriteFile(filepath.Join(ctx.WorkDir, "test.png"), png1x1, 0644); err != nil {
		t.Fatalf("Failed to write test.png: %v", err)
	}

	viewImagePrompt := "Use the view tool to look at the file test.png and describe what you see"

	t.Run("vision disabled then enabled via setModel", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Model: "claude-sonnet-5", Inference: copilot.SessionInferenceOptions{Capabilities: &copilot.ModelCapabilitiesOverride{
			Supports: &copilot.ModelCapabilitiesOverrideSupports{
				Vision: copilot.Bool(false),
			},
		}},
		})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}

		// Turn 1: vision off — no image_url expected
		if _, err := session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: viewImagePrompt}); err != nil {
			t.Fatalf("Failed to send message: %v", err)
		}

		trafficAfterT1, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("Failed to get exchanges: %v", err)
		}
		if hasImageURLContent(trafficAfterT1) {
			t.Error("Expected no image_url content parts when vision is disabled")
		}

		// Switch vision on
		if err := session.SetModel(t.Context(), "claude-sonnet-5", &copilot.SetModelOptions{
			ModelCapabilities: &copilot.ModelCapabilitiesOverride{
				Supports: &copilot.ModelCapabilitiesOverrideSupports{
					Vision: copilot.Bool(true),
				},
			},
		}); err != nil {
			t.Fatalf("SetModel returned error: %v", err)
		}

		// Turn 2: vision on — image_url expected in new exchanges
		if _, err := session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: viewImagePrompt}); err != nil {
			t.Fatalf("Failed to send second message: %v", err)
		}

		trafficAfterT2, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("Failed to get exchanges after turn 2: %v", err)
		}
		newExchanges := trafficAfterT2[len(trafficAfterT1):]
		if !hasImageURLContent(newExchanges) {
			t.Error("Expected image_url content parts when vision is enabled")
		}
	})

	t.Run("vision enabled then disabled via setModel", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Model: "claude-sonnet-5", Inference: copilot.SessionInferenceOptions{Capabilities: &copilot.ModelCapabilitiesOverride{
			Supports: &copilot.ModelCapabilitiesOverrideSupports{
				Vision: copilot.Bool(true),
			},
		}},
		})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}

		// Turn 1: vision on — image_url expected
		if _, err := session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: viewImagePrompt}); err != nil {
			t.Fatalf("Failed to send message: %v", err)
		}

		trafficAfterT1, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("Failed to get exchanges: %v", err)
		}
		if !hasImageURLContent(trafficAfterT1) {
			t.Error("Expected image_url content parts when vision is enabled")
		}

		// Switch vision off
		if err := session.SetModel(t.Context(), "claude-sonnet-5", &copilot.SetModelOptions{
			ModelCapabilities: &copilot.ModelCapabilitiesOverride{
				Supports: &copilot.ModelCapabilitiesOverrideSupports{
					Vision: copilot.Bool(false),
				},
			},
		}); err != nil {
			t.Fatalf("SetModel returned error: %v", err)
		}

		// Turn 2: vision off — no image_url expected in new exchanges
		if _, err := session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: viewImagePrompt}); err != nil {
			t.Fatalf("Failed to send second message: %v", err)
		}

		trafficAfterT2, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("Failed to get exchanges after turn 2: %v", err)
		}
		newExchanges := trafficAfterT2[len(trafficAfterT1):]
		if hasImageURLContent(newExchanges) {
			t.Error("Expected no image_url content parts when vision is disabled")
		}
	})
}

func TestSessionConfigNewOptionsCopilotRequestE2E(t *testing.T) {
	t.Parallel()
	testharness.SkipIfInProcess(t, "an LLM inference provider is process-global in-process")
	t.Run("should enable citations for Anthropic file attachments on create", func(t *testing.T) {
		ctx := testharness.NewTestContext(t)
		transport := &recordingTransport{}
		handler := &copilot.CopilotRequestHandler{Transport: transport}
		client := newCopilotRequestClient(ctx, handler)
		t.Cleanup(func() { client.ForceStop() })

		if err := client.Start(t.Context()); err != nil {
			t.Fatalf("Failed to start client: %v", err)
		}

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{GitHub: copilot.SessionGitHubOptions{Capi: &copilot.CapiSessionOptions{Enabled: copilot.Bool(false)}}, Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Model: "claude-sonnet-5", Inference: byokInference(createAnthropicProvider(), copilot.SessionInferenceOptions{EnableCitations: copilot.Bool(true)})})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		defer session.Disconnect()

		_, err = session.SendAndWait(t.Context(), copilot.MessageOptions{
			Prompt:      "Summarize the attached PDF with citations enabled.",
			Attachments: []copilot.Attachment{createPDFAttachment()},
		})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}

		inference := transport.inferenceRecords()
		if len(inference) != 1 {
			t.Fatalf("Expected exactly one intercepted inference request, got %d", len(inference))
		}
		assertAnthropicDocumentCitationsEnabled(t, inference[0].body)
	})

	t.Run("should enable citations for Anthropic file attachments on resume", func(t *testing.T) {
		ctx := testharness.NewTestContext(t)
		transport := &recordingTransport{}
		handler := &copilot.CopilotRequestHandler{Transport: transport}
		const connectionToken = "go-citation-resume-token"
		server := ctx.NewClient(func(o *copilot.ClientOptions) {
			useTCP(o, 0, connectionToken)
			o.Models.RequestHandler = handler
		})
		t.Cleanup(func() { server.ForceStop() })

		if err := server.Start(t.Context()); err != nil {
			t.Fatalf("Failed to start server client: %v", err)
		}

		session1, err := server.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		defer session1.Disconnect()

		runtimePort := server.RuntimePort()
		if runtimePort == 0 {
			t.Fatal("Expected non-zero runtime port")
		}
		resumeClient := ctx.NewClient(func(o *copilot.ClientOptions) {
			o.Runtime = copilot.TCPConnection{
				Host: "localhost", Port: runtimePort,
				ConnectionToken: connectionToken,
			}
		})
		t.Cleanup(func() { resumeClient.ForceStop() })

		session2, err := resumeClient.ResumeSession(t.Context(), session1.SessionID, &copilot.ResumeSessionOptions{GitHub: copilot.SessionGitHubOptions{Capi: &copilot.CapiSessionOptions{Enabled: copilot.Bool(false)}}, Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Model: "claude-sonnet-5", Inference: byokInference(createAnthropicProvider(), copilot.SessionInferenceOptions{EnableCitations: copilot.Bool(true)})})
		if err != nil {
			t.Fatalf("ResumeSession failed: %v", err)
		}
		defer session2.Disconnect()

		_, err = session2.SendAndWait(t.Context(), copilot.MessageOptions{
			Prompt:      "Summarize the attached PDF with citations enabled.",
			Attachments: []copilot.Attachment{createPDFAttachment()},
		})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}

		inference := transport.inferenceRecords()
		if len(inference) != 1 {
			t.Fatalf("Expected exactly one intercepted inference request, got %d", len(inference))
		}
		assertAnthropicDocumentCitationsEnabled(t, inference[0].body)
	})
}

// TestSessionConfigExtras mirrors the additional Should_* tests in dotnet/test/SessionConfigTests.cs:
//
//	Should_Use_Custom_SessionId
//	Should_Forward_ClientName_In_UserAgent
//	Should_Forward_Custom_Provider_Headers_On_Create
//	Should_Forward_Custom_Provider_Headers_On_Resume
//	Should_Use_WorkingDirectory_For_Tool_Execution
//	Should_Apply_WorkingDirectory_On_Session_Resume
//	Should_Apply_SystemMessage_On_Session_Resume
//	Should_Apply_AvailableTools_On_Session_Resume
func TestSessionConfigExtrasE2E(t *testing.T) {
	t.Parallel()
	const providerHeaderName = "x-copilot-sdk-provider-header"
	const clientName = "go-public-surface-client"

	ctx := testharness.NewTestContext(t)
	client := ctx.NewClient()
	t.Cleanup(func() { client.ForceStop() })

	if err := client.Start(t.Context()); err != nil {
		t.Fatalf("Failed to start client: %v", err)
	}

	t.Run("should use custom sessionId", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		requestedSessionID := newUUID(t)
		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, SessionID: requestedSessionID})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		if session.SessionID != requestedSessionID {
			t.Errorf("Expected SessionID=%q, got %q", requestedSessionID, session.SessionID)
		}

		messages, err := session.GetEvents(t.Context())
		if err != nil {
			t.Fatalf("GetEvents failed: %v", err)
		}
		if len(messages) == 0 || messages[0].Type() != copilot.SessionEventTypeSessionStart {
			t.Fatalf("Expected first event to be session.start, got %+v", messages)
		}
		startData := messages[0].Data.(*copilot.SessionStartData)
		if startData.SessionID != requestedSessionID {
			t.Errorf("Expected start.SessionID=%q, got %q", requestedSessionID, startData.SessionID)
		}
	})

	t.Run("should forward clientName in userAgent", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, ClientName: clientName})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}

		_, err = session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "What is 1+1?"})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}

		exchanges, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("GetExchanges failed: %v", err)
		}
		if len(exchanges) != 1 {
			t.Fatalf("Expected exactly 1 exchange, got %d", len(exchanges))
		}
		if !exchangeHasHeader(exchanges[0], "user-agent", clientName) {
			t.Errorf("Expected user-agent to contain %q, got %v", clientName, exchanges[0].RequestHeaders)
		}
	})

	t.Run("should forward custom provider headers on create", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{GitHub: copilot.SessionGitHubOptions{Capi: &copilot.CapiSessionOptions{Enabled: copilot.Bool(false)}}, Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Model: "claude-sonnet-5", Inference: byokInference(createProxyProvider(ctx, providerHeaderName, "create-provider-header"), copilot.SessionInferenceOptions{})})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}

		message, err := session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "What is 1+1?"})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}
		if !assistantMessageContains(message, "2") {
			t.Errorf("Expected response to contain '2', got %v", message)
		}

		exchanges, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("GetExchanges failed: %v", err)
		}
		if len(exchanges) != 1 {
			t.Fatalf("Expected exactly 1 exchange, got %d", len(exchanges))
		}
		if !exchangeHasHeader(exchanges[0], "authorization", "Bearer test-provider-key") {
			t.Errorf("Expected authorization header to contain 'Bearer test-provider-key', got %v", exchanges[0].RequestHeaders)
		}
		if !exchangeHasHeader(exchanges[0], providerHeaderName, "create-provider-header") {
			t.Errorf("Expected %s header to contain 'create-provider-header', got %v", providerHeaderName, exchanges[0].RequestHeaders)
		}
	})

	t.Run("should forward custom provider headers on resume", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session1, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		sessionID := session1.SessionID
		t.Cleanup(func() { _ = session1.Disconnect() })

		session2, err := client.ResumeSession(t.Context(), sessionID, &copilot.ResumeSessionOptions{GitHub: copilot.SessionGitHubOptions{Capi: &copilot.CapiSessionOptions{Enabled: copilot.Bool(false)}}, Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Model: "claude-sonnet-5", Inference: byokInference(createProxyProvider(ctx, providerHeaderName, "resume-provider-header"), copilot.SessionInferenceOptions{})})
		if err != nil {
			t.Fatalf("ResumeSession failed: %v", err)
		}
		t.Cleanup(func() { _ = session2.Disconnect() })

		message, err := session2.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "What is 2+2?"})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}
		if !assistantMessageContains(message, "4") {
			t.Errorf("Expected response to contain '4', got %v", message)
		}

		exchanges, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("GetExchanges failed: %v", err)
		}
		if len(exchanges) != 1 {
			t.Fatalf("Expected exactly 1 exchange, got %d", len(exchanges))
		}
		if !exchangeHasHeader(exchanges[0], "authorization", "Bearer test-provider-key") {
			t.Errorf("Expected authorization header to contain 'Bearer test-provider-key', got %v", exchanges[0].RequestHeaders)
		}
		if !exchangeHasHeader(exchanges[0], providerHeaderName, "resume-provider-header") {
			t.Errorf("Expected %s header to contain 'resume-provider-header', got %v", providerHeaderName, exchanges[0].RequestHeaders)
		}
	})

	t.Run("should forward provider wire model", func(t *testing.T) {
		// Verifies that ProviderConfig.WireModel overrides the model name sent to
		// the provider API, while CreateSessionOptions.Model still drives runtime
		// configuration lookup (capabilities, prompts, reasoning behavior).
		// MaxOutputTokens is also set here to confirm the SDK accepts it without
		// serialization errors; the CLI does not echo it as `max_tokens` on the
		// OpenAI-style wire request, so we don't assert on it directly (see unit
		// tests for serialization coverage).
		ctx.ConfigureForTest(t)

		maxOutputTokens := 1024
		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{GitHub: copilot.SessionGitHubOptions{Capi: &copilot.CapiSessionOptions{Enabled: copilot.Bool(false)}}, Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Model: "claude-sonnet-5", Inference: byokInference(&copilot.ProviderConfig{
			Type:            "openai",
			BaseURL:         ctx.ProxyURL,
			APIKey:          "test-provider-key",
			WireModel:       "test-wire-model",
			MaxOutputTokens: maxOutputTokens,
		}, copilot.SessionInferenceOptions{}),
		})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}

		_, err = session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "What is 1+1?"})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}

		exchanges, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("GetExchanges failed: %v", err)
		}
		if len(exchanges) != 1 {
			t.Fatalf("Expected exactly 1 exchange, got %d", len(exchanges))
		}
		if exchanges[0].Request.Model != "test-wire-model" {
			t.Errorf("Expected request model to be 'test-wire-model', got %q", exchanges[0].Request.Model)
		}
	})

	t.Run("should use provider model id as wire model", func(t *testing.T) {
		// A registered model's ModelID is the wire model when WireModel is omitted.
		ctx.ConfigureForTest(t)

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Model: "claude-sonnet-5", GitHub: copilot.SessionGitHubOptions{Capi: &copilot.CapiSessionOptions{Enabled: copilot.Bool(false)}}, Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Inference: byokInference(&copilot.ProviderConfig{
			Type:    "openai",
			BaseURL: ctx.ProxyURL,
			APIKey:  "test-provider-key",
			ModelID: "claude-sonnet-5",
		}, copilot.SessionInferenceOptions{}),
		})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}

		_, err = session.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "What is 1+1?"})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}

		exchanges, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("GetExchanges failed: %v", err)
		}
		if len(exchanges) != 1 {
			t.Fatalf("Expected exactly 1 exchange, got %d", len(exchanges))
		}
		if exchanges[0].Request.Model != "claude-sonnet-5" {
			t.Errorf("Expected request model to be 'claude-sonnet-5', got %q", exchanges[0].Request.Model)
		}
	})

	t.Run("should use workingDirectory for tool execution", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		subDir := filepath.Join(ctx.WorkDir, "subproject")
		if err := os.MkdirAll(subDir, 0755); err != nil {
			t.Fatalf("MkdirAll failed: %v", err)
		}
		if err := os.WriteFile(filepath.Join(subDir, "marker.txt"), []byte("I am in the subdirectory"), 0644); err != nil {
			t.Fatalf("WriteFile failed: %v", err)
		}

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, WorkingDirectory: subDir})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}

		message, err := session.SendAndWait(t.Context(), copilot.MessageOptions{
			Prompt: "Read the file marker.txt and tell me what it says",
		})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}
		if !assistantMessageContains(message, "subdirectory") {
			t.Errorf("Expected response to contain 'subdirectory', got %v", message)
		}
	})

	t.Run("should apply workingDirectory on session resume", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		subDir := filepath.Join(ctx.WorkDir, "resume-subproject")
		if err := os.MkdirAll(subDir, 0755); err != nil {
			t.Fatalf("MkdirAll failed: %v", err)
		}
		if err := os.WriteFile(filepath.Join(subDir, "resume-marker.txt"), []byte("I am in the resume working directory"), 0644); err != nil {
			t.Fatalf("WriteFile failed: %v", err)
		}

		session1, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		sessionID := session1.SessionID
		t.Cleanup(func() { _ = session1.Disconnect() })

		session2, err := client.ResumeSession(t.Context(), sessionID, &copilot.ResumeSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, WorkingDirectory: subDir})
		if err != nil {
			t.Fatalf("ResumeSession failed: %v", err)
		}
		t.Cleanup(func() { _ = session2.Disconnect() })

		message, err := session2.SendAndWait(t.Context(), copilot.MessageOptions{
			Prompt: "Read the file resume-marker.txt and tell me what it says",
		})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}
		if !assistantMessageContains(message, "resume working directory") {
			t.Errorf("Expected response to contain 'resume working directory', got %v", message)
		}
	})

	t.Run("should apply systemMessage on session resume", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session1, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		sessionID := session1.SessionID
		t.Cleanup(func() { _ = session1.Disconnect() })

		const resumeInstruction = "End the response with RESUME_SYSTEM_MESSAGE_SENTINEL."
		session2, err := client.ResumeSession(t.Context(), sessionID, &copilot.ResumeSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, SystemMessage: &copilot.SystemMessageConfig{
			Mode:    "append",
			Content: resumeInstruction,
		},
		})
		if err != nil {
			t.Fatalf("ResumeSession failed: %v", err)
		}
		t.Cleanup(func() { _ = session2.Disconnect() })

		message, err := session2.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "What is 1+1?"})
		if err != nil {
			t.Fatalf("SendAndWait failed: %v", err)
		}
		if !assistantMessageContains(message, "RESUME_SYSTEM_MESSAGE_SENTINEL") {
			t.Errorf("Expected response to contain 'RESUME_SYSTEM_MESSAGE_SENTINEL', got %v", message)
		}

		exchanges, err := ctx.GetExchanges()
		if err != nil {
			t.Fatalf("GetExchanges failed: %v", err)
		}
		if len(exchanges) != 1 {
			t.Fatalf("Expected exactly 1 exchange, got %d", len(exchanges))
		}
		if !strings.Contains(getSystemMessage(exchanges[0]), resumeInstruction) {
			t.Errorf("Expected system message to contain %q", resumeInstruction)
		}
	})

	t.Run("should apply availableTools on session resume", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session1, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		sessionID := session1.SessionID
		t.Cleanup(func() { _ = session1.Disconnect() })

		session2, err := client.ResumeSession(t.Context(), sessionID, &copilot.ResumeSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, Tools: copilot.SessionToolOptions{AvailableTools: []string{"view"}}})
		if err != nil {
			t.Fatalf("ResumeSession failed: %v", err)
		}
		t.Cleanup(func() { _ = session2.Disconnect() })

		_, err = session2.Send(t.Context(), copilot.MessageOptions{Prompt: "What is 1+1?"})
		if err != nil {
			t.Fatalf("Send failed: %v", err)
		}

		exchanges := ctx.WaitForExchanges(t, 1)
		if len(exchanges) != 1 {
			t.Fatalf("Expected exactly 1 exchange, got %d", len(exchanges))
		}
		toolNames := getToolNames(exchanges[0])
		if len(toolNames) != 1 || toolNames[0] != "view" {
			t.Errorf("Expected toolNames=[view], got %v", toolNames)
		}
	})

	t.Run("should apply GitHub MCP tool config on create", func(t *testing.T) {
		ctx.ConfigureForTest(t)
		enableAllTools := true
		enableInsidersMode := true
		disableFormDeferral := true

		session, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, EnableConfigDiscovery: copilot.Bool(true), MCP: copilot.SessionMCPOptions{EnableApps: true}, GitHub: copilot.SessionGitHubOptions{MCP: &copilot.GitHubMCPToolConfig{
			EnableAllTools:      &enableAllTools,
			AdditionalToolsets:  []string{"actions"},
			AdditionalTools:     []string{"get_me"},
			EnableInsidersMode:  &enableInsidersMode,
			DisableFormDeferral: &disableFormDeferral,
		}},
		})
		if err != nil {
			t.Fatalf("CreateSession failed: %v", err)
		}
		t.Cleanup(func() { _ = session.Disconnect() })

		assertGitHubMCPConfigApplied(t, ctx, session)
	})
}

func assertGitHubMCPConfigApplied(t *testing.T, ctx *testharness.TestContext, session *copilot.Session) {
	t.Helper()
	if _, err := session.RPC.MCP.List(t.Context()); err != nil {
		t.Fatalf("MCP.List failed: %v", err)
	}
	deadline := time.Now().Add(60 * time.Second)
	var lastRequests []testharness.CapturedRequest
	for {
		requests, err := ctx.GetRequests()
		if err == nil {
			lastRequests = requests
			var writableRequest *testharness.CapturedRequest
			hasReadonlyRequest := false
			for i := range requests {
				request := &requests[i]
				if request.URL == "/mcp/readonly" {
					hasReadonlyRequest = true
				}
				if request.Method == http.MethodPost && request.URL == "/mcp" {
					writableRequest = request
				}
			}
			if writableRequest != nil {
				if hasReadonlyRequest {
					t.Fatalf("Expected writable GitHub MCP endpoint, got requests: %+v", requests)
				}
				assertCapturedHeader(t, writableRequest.Headers, "x-mcp-toolsets", "all")
				assertCapturedHeader(t, writableRequest.Headers, "x-mcp-insiders", "true")
				return
			}
		}
		if !time.Now().Before(deadline) {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("Timed out waiting for configured GitHub MCP request; captured: %+v", lastRequests)
}

func assertCapturedHeader(t *testing.T, headers map[string]json.RawMessage, name, expected string) {
	t.Helper()
	var actual string
	if err := json.Unmarshal(headers[name], &actual); err != nil {
		t.Fatalf("Failed to decode %s header: %v", name, err)
	}
	if actual != expected {
		t.Fatalf("Expected %s=%q, got %q", name, expected, actual)
	}
}

// createProxyProvider returns a ProviderConfig that points at the test proxy and
// includes a custom header — used for the "should forward custom provider headers" tests.
func createProxyProvider(ctx *testharness.TestContext, headerName, headerValue string) *copilot.ProviderConfig {
	return &copilot.ProviderConfig{
		Type:    "openai",
		BaseURL: ctx.ProxyURL,
		APIKey:  "test-provider-key",
		Headers: map[string]string{
			headerName: headerValue,
		},
	}
}

// newUUID generates a v4 UUID string for tests that need a custom session ID.
func newUUID(t *testing.T) string {
	t.Helper()
	id, err := uuid.NewRandomFromReader(rand.Reader)
	if err != nil {
		t.Fatalf("UUID generation failed: %v", err)
	}
	return id.String()
}

// assistantMessageContains returns true when the SendAndWait return value is a
// non-nil assistant.message event whose content contains the given substring.
func assistantMessageContains(message *copilot.SessionEvent, substring string) bool {
	if message == nil {
		return false
	}
	data, ok := message.Data.(*copilot.AssistantMessageData)
	if !ok {
		return false
	}
	return strings.Contains(data.Content, substring)
}
