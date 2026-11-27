package e2e

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	copilot "github.com/microsoft/copilot-sdk/go/v2"
	"github.com/microsoft/copilot-sdk/go/v2/internal/e2e/testharness"
	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func TestSessionFSE2E(t *testing.T) {
	t.Parallel()
	ctx := testharness.NewTestContext(t)
	providerRoot := t.TempDir()
	sessionStatePath := createSessionStatePath(t)
	sessionFSConfig := &copilot.SessionFSConfig{
		InitialWorkingDirectory: "/",
		SessionStatePath:        sessionStatePath,
		Conventions:             rpc.SessionFSSetProviderConventionsPosix,
	}
	createSessionFSHandler := func(session *copilot.Session) copilot.SessionFSProvider {
		return &testSessionFSHandler{
			root:      providerRoot,
			sessionID: session.SessionID,
		}
	}
	p := func(sessionID string, path string) string {
		return providerPath(providerRoot, sessionID, path)
	}

	client := ctx.NewClient(func(opts *copilot.ClientOptions) {
		opts.SessionFS = sessionFSConfig
	})
	t.Cleanup(func() { client.ForceStop() })

	t.Run("should view an image that exists only in the binary session fs provider", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		imagePath := "/sdk-provider-image.png"
		imageBytes, err := base64.StdEncoding.DecodeString("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==")
		if err != nil {
			t.Fatal(err)
		}
		binaryConfig := *sessionFSConfig
		binaryConfig.Capabilities = &copilot.SessionFSCapabilities{Binary: true}
		binaryClient := ctx.NewClient(func(opts *copilot.ClientOptions) {
			opts.SessionFS = &binaryConfig
		})
		t.Cleanup(func() { binaryClient.ForceStop() })
		readPaths := make(chan string, 1)
		session, err := binaryClient.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, State: copilot.SessionStateOptions{CreateSessionFSProvider: func(session *copilot.Session) copilot.SessionFSProvider {
			return &testBinarySessionFSHandler{
				testSessionFSHandler: &testSessionFSHandler{root: providerRoot, sessionID: session.SessionID},
				readPaths:            readPaths,
			}
		}},
		})
		if err != nil {
			t.Fatalf("Failed to create binary session: %v", err)
		}
		storedPath := p(session.SessionID, imagePath)
		if err := os.MkdirAll(filepath.Dir(storedPath), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(storedPath, imageBytes, 0o644); err != nil {
			t.Fatal(err)
		}
		if _, err := os.Stat(imagePath); !os.IsNotExist(err) {
			t.Fatalf("Image unexpectedly exists on the runtime filesystem: %v", err)
		}

		msg, err := session.SendAndWait(t.Context(), copilot.MessageOptions{
			Prompt: "Use the view tool to view /sdk-provider-image.png, then reply with exactly SDK_PROVIDER_IMAGE_DONE.",
		})
		if err != nil {
			t.Fatalf("Failed to view provider image: %v", err)
		}
		if msg == nil {
			t.Fatal("Expected a final assistant response")
		}
		response, ok := msg.Data.(*copilot.AssistantMessageData)
		if !ok || !strings.Contains(response.Content, "SDK_PROVIDER_IMAGE_DONE") {
			t.Fatalf("Expected final image response, got %#v", msg)
		}
		select {
		case path := <-readPaths:
			if path != imagePath {
				t.Fatalf("Expected binary read of %q, got %q", imagePath, path)
			}
		default:
			t.Fatal("Expected a binary read from the provider")
		}
		events, err := session.GetEvents(t.Context())
		if err != nil {
			t.Fatalf("Failed to read session events: %v", err)
		}
		foundImage := false
		for _, event := range events {
			if event.Type() == copilot.SessionEventTypeSessionBinaryAsset {
				t.Fatal("Internal binary asset event escaped public session history")
			}
			complete, ok := event.Data.(*copilot.ToolExecutionCompleteData)
			if !ok || !complete.Success || complete.Result == nil {
				continue
			}
			for _, result := range complete.Result.BinaryResultsForLlm {
				if image, ok := result.(*rpc.PersistedBinaryImage); ok &&
					image.Type() == rpc.PersistedBinaryResultTypeImage &&
					image.MIMEType == "image/png" && image.Data == base64.StdEncoding.EncodeToString(imageBytes) {
					foundImage = true
				}
			}
		}
		if !foundImage {
			t.Fatal("Expected an inline tool result with the exact provider image bytes")
		}
		if err := session.Disconnect(); err != nil {
			t.Fatalf("Failed to disconnect session: %v", err)
		}
	})

	t.Run("should load session data from fs provider on resume", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		session1, err := client.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, State: copilot.SessionStateOptions{CreateSessionFSProvider: createSessionFSHandler}})
		if err != nil {
			t.Fatalf("Failed to create session: %v", err)
		}
		sessionID := session1.SessionID

		msg, err := session1.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "What is 50 + 50?"})
		if err != nil {
			t.Fatalf("Failed to send first message: %v", err)
		}
		content := ""
		if msg != nil {
			if d, ok := msg.Data.(*copilot.AssistantMessageData); ok {
				content = d.Content
			}
		}
		if !strings.Contains(content, "100") {
			t.Fatalf("Expected response to contain 100, got %q", content)
		}
		if err := session1.Disconnect(); err != nil {
			t.Fatalf("Failed to disconnect first session: %v", err)
		}

		eventsContent, err := os.ReadFile(p(sessionID, sessionStatePath+"/events.jsonl"))
		if err != nil {
			t.Fatalf("Expected events file to exist before resume: %v", err)
		}
		if !strings.Contains(string(eventsContent), "100") {
			t.Fatalf("Expected events file to contain 100, got %q", string(eventsContent))
		}

		session2, err := client.ResumeSession(t.Context(), sessionID, &copilot.ResumeSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}, State: copilot.SessionStateOptions{CreateSessionFSProvider: createSessionFSHandler}})
		if err != nil {
			t.Fatalf("Failed to resume session: %v", err)
		}

		msg2, err := session2.SendAndWait(t.Context(), copilot.MessageOptions{Prompt: "What is that times 3?"})
		if err != nil {
			t.Fatalf("Failed to send second message: %v", err)
		}
		content2 := ""
		if msg2 != nil {
			if d, ok := msg2.Data.(*copilot.AssistantMessageData); ok {
				content2 = d.Content
			}
		}
		if !strings.Contains(content2, "300") {
			t.Fatalf("Expected response to contain 300, got %q", content2)
		}
		if err := session2.Disconnect(); err != nil {
			t.Fatalf("Failed to disconnect resumed session: %v", err)
		}
	})

	t.Run("should reject setProvider when sessions already exist", func(t *testing.T) {
		ctx.ConfigureForTest(t)

		client1 := ctx.NewClient(func(opts *copilot.ClientOptions) {
			useTCP(opts, 0, "")
		})
		t.Cleanup(func() { client1.ForceStop() })

		if _, err := client1.CreateSession(t.Context(), &copilot.CreateSessionOptions{Permissions: copilot.SessionPermissionOptions{OnRequest: copilot.PermissionHandler.ApproveAll}}); err != nil {
			t.Fatalf("Failed to create initial session: %v", err)
		}

		runtimePort := client1.RuntimePort()
		if runtimePort == 0 {
			t.Fatalf("Expected non-zero port from TCP mode client")
		}

		client2 := copilot.NewClient(&copilot.ClientOptions{Mode: copilot.ModeCodingAgent,
			Runtime:   copilot.TCPConnection{Host: "localhost", Port: runtimePort},
			SessionFS: sessionFSConfig,
		})
		t.Cleanup(func() { client2.ForceStop() })

		if err := client2.Start(t.Context()); err == nil {
			t.Fatal("Expected Start to fail when SessionFS provider is set after sessions already exist")
		}
	})
}

func createSessionStatePath(t *testing.T) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		return "/session-state"
	}
	return filepath.ToSlash(filepath.Join(t.TempDir(), "session-state"))
}

type testSessionFSHandler struct {
	root      string
	sessionID string
}

type testBinarySessionFSHandler struct {
	*testSessionFSHandler
	readPaths chan<- string
}

func (h *testBinarySessionFSHandler) ReadFileBytes(path string) ([]byte, error) {
	select {
	case h.readPaths <- path:
	default:
	}
	return os.ReadFile(providerPath(h.root, h.sessionID, path))
}

func (h *testBinarySessionFSHandler) WriteFileBytes(path string, content []byte, mode *int) error {
	fullPath := providerPath(h.root, h.sessionID, path)
	if err := os.MkdirAll(filepath.Dir(fullPath), 0o755); err != nil {
		return err
	}
	perm := os.FileMode(0o666)
	if mode != nil {
		perm = os.FileMode(*mode)
	}
	return os.WriteFile(fullPath, content, perm)
}

func (h *testSessionFSHandler) ReadFile(path string) (string, error) {
	content, err := os.ReadFile(providerPath(h.root, h.sessionID, path))
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *testSessionFSHandler) WriteFile(path string, content string, mode *int) error {
	fullPath := providerPath(h.root, h.sessionID, path)
	if err := os.MkdirAll(filepath.Dir(fullPath), 0o755); err != nil {
		return err
	}
	perm := os.FileMode(0o666)
	if mode != nil {
		perm = os.FileMode(*mode)
	}
	return os.WriteFile(fullPath, []byte(content), perm)
}

func (h *testSessionFSHandler) AppendFile(path string, content string, mode *int) error {
	fullPath := providerPath(h.root, h.sessionID, path)
	if err := os.MkdirAll(filepath.Dir(fullPath), 0o755); err != nil {
		return err
	}
	perm := os.FileMode(0o666)
	if mode != nil {
		perm = os.FileMode(*mode)
	}
	f, err := os.OpenFile(fullPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, perm)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.WriteString(content)
	return err
}

func (h *testSessionFSHandler) Exists(path string) (bool, error) {
	_, err := os.Stat(providerPath(h.root, h.sessionID, path))
	if err == nil {
		return true, nil
	}
	if os.IsNotExist(err) {
		return false, nil
	}
	return false, err
}

func (h *testSessionFSHandler) Stat(path string) (*copilot.SessionFSFileInfo, error) {
	info, err := os.Stat(providerPath(h.root, h.sessionID, path))
	if err != nil {
		return nil, err
	}
	ts := info.ModTime().UTC()
	return &copilot.SessionFSFileInfo{
		IsFile:      !info.IsDir(),
		IsDirectory: info.IsDir(),
		Size:        info.Size(),
		Mtime:       ts,
		Birthtime:   ts,
	}, nil
}

func (h *testSessionFSHandler) MakeDirectory(path string, recursive bool, mode *int) error {
	fullPath := providerPath(h.root, h.sessionID, path)
	perm := os.FileMode(0o777)
	if mode != nil {
		perm = os.FileMode(*mode)
	}
	if recursive {
		return os.MkdirAll(fullPath, perm)
	}
	return os.Mkdir(fullPath, perm)
}

func (h *testSessionFSHandler) ReadDirectory(path string) ([]string, error) {
	entries, err := os.ReadDir(providerPath(h.root, h.sessionID, path))
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	return names, nil
}

func (h *testSessionFSHandler) ReadDirectoryWithTypes(path string) ([]rpc.SessionFSReaddirWithTypesEntry, error) {
	entries, err := os.ReadDir(providerPath(h.root, h.sessionID, path))
	if err != nil {
		return nil, err
	}
	result := make([]rpc.SessionFSReaddirWithTypesEntry, 0, len(entries))
	for _, entry := range entries {
		entryType := rpc.SessionFSReaddirWithTypesEntryTypeFile
		if entry.IsDir() {
			entryType = rpc.SessionFSReaddirWithTypesEntryTypeDirectory
		}
		result = append(result, rpc.SessionFSReaddirWithTypesEntry{
			Name: entry.Name(),
			Type: entryType,
		})
	}
	return result, nil
}

func (h *testSessionFSHandler) Remove(path string, recursive bool, force bool) error {
	fullPath := providerPath(h.root, h.sessionID, path)
	var err error
	if recursive {
		err = os.RemoveAll(fullPath)
	} else {
		err = os.Remove(fullPath)
	}
	if err != nil && force && os.IsNotExist(err) {
		return nil
	}
	return err
}

func (h *testSessionFSHandler) Rename(src string, dest string) error {
	destPath := providerPath(h.root, h.sessionID, dest)
	if err := os.MkdirAll(filepath.Dir(destPath), 0o755); err != nil {
		return err
	}
	return os.Rename(providerPath(h.root, h.sessionID, src), destPath)
}

func providerPath(root string, sessionID string, path string) string {
	trimmed := strings.TrimPrefix(path, "/")
	if trimmed == "" {
		return filepath.Join(root, sessionID)
	}
	return filepath.Join(root, sessionID, filepath.FromSlash(trimmed))
}
