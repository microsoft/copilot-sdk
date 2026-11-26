/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.microsoft.copilot.generated.rpc.SessionToolsExecuteParams;
import com.microsoft.copilot.rpc.CopilotClientMode;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.rpc.RuntimeConnection;
import com.microsoft.copilot.rpc.SessionPermissionOptions;
import com.microsoft.copilot.rpc.SessionToolOptions;
import com.microsoft.copilot.rpc.UrlPermissionPrompt;

/**
 * Checks that a Java permission handler can read a URL prompt through
 * {@link UrlPermissionPrompt} and answer with the {@code domain} of an
 * approve-for-session decision.
 * <p>
 * The test calls {@code web_fetch} directly, so no model request is made. A
 * plain-HTTP forward proxy stands in for the internet: {@code web_fetch} sends
 * every {@code http://} request to it, so nothing leaves the machine, and the
 * URLs it records are the fetches the permission layer let through.
 */
@Tag("e2e")
public class UrlPermissionApprovalE2ETest {

    private static final String LOOPBACK_ONLY = "127.0.0.1,localhost,::1";

    private static E2ETestContext ctx;

    @BeforeAll
    static void setup() throws Exception {
        ctx = E2ETestContext.create();
    }

    @AfterAll
    static void teardown() throws Exception {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    void shouldApproveOfferedParentDomainForSession() throws Exception {
        ctx.configureForTest("url_permissions", "should_approve_offered_parent_domain_for_session");
        String first = "http://api.example-docs.com/a";

        try (var app = Fetcher.start((prompt, offered) -> first.equals(prompt.getUrl())
                ? PermissionRequestResult.approveUrlForSession(offered)
                : PermissionRequestResult.reject(null))) {
            app.fetch(first);
            assertEquals(List.of(first + " offered http://*.example-docs.com"), app.prompts);

            app.fetch("http://guides.example-docs.com/b");
            app.fetch("http://example-docs.com/c");
            assertEquals(1, app.prompts.size(), "hosts under the approved domain must not prompt again");

            app.fetch("http://example-docs.org/d");
            assertEquals(2, app.prompts.size(), "a different domain must prompt");
            assertEquals(List.of(first, "http://guides.example-docs.com/b", "http://example-docs.com/c"),
                    app.proxy.requests, "only approved fetches may reach the network");
        }
    }

    private static final class Fetcher implements AutoCloseable {

        final ForwardProxy proxy;
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final CopilotClient client;
        final CopilotSession session;

        private Fetcher(ForwardProxy proxy, BiFunction<UrlPermissionPrompt, String, PermissionRequestResult> decide)
                throws Exception {
            this.proxy = proxy;
            Map<String, String> env = new HashMap<>(ctx.getEnvironment());
            for (String name : List.of("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")) {
                env.put(name, proxy.url());
            }
            env.put("NO_PROXY", LOOPBACK_ONLY);
            env.put("no_proxy", LOOPBACK_ONLY);
            env.put("TOOL_SEARCH_DISABLED", "true");
            // The proxy variables reach the runtime only through a child process.
            this.client = ctx.createClient(new CopilotClientOptions().setMode(CopilotClientMode.CODING_AGENT)
                    .setRuntime(RuntimeConnection.forSpawnedStdio().setEnvironment(env)));
            this.session = client.createSession(ctx.newSessionConfig().setConfigDirectory(ctx.getHomeDir().toString())
                    .setEnableConfigDiscovery(false)
                    .setTools(new SessionToolOptions().setAvailableTools(List.of("web_fetch")))
                    .setPermissions(new SessionPermissionOptions().setOnRequest((request, invocation) -> {
                        var prompt = UrlPermissionPrompt.from(invocation.getPromptRequest());
                        if (prompt == null) {
                            return CompletableFuture.completedFuture(PermissionRequestResult.approveOnce());
                        }
                        String offered = prompt.getParentDomainPattern();
                        prompts.add(offered == null ? prompt.getUrl() : prompt.getUrl() + " offered " + offered);
                        return CompletableFuture.completedFuture(decide.apply(prompt, offered));
                    }))).get(60, TimeUnit.SECONDS);
            session.getRpc().tools.initializeAndValidate().get(60, TimeUnit.SECONDS);
        }

        static Fetcher start(BiFunction<UrlPermissionPrompt, String, PermissionRequestResult> decide) throws Exception {
            var proxy = new ForwardProxy();
            try {
                return new Fetcher(proxy, decide);
            } catch (Exception e) {
                proxy.close();
                throw e;
            }
        }

        void fetch(String url) throws Exception {
            session.getRpc().tools.execute(SessionToolsExecuteParams.builder().name("web_fetch")
                    .arguments(Map.of("url", url, "raw", true)).build()).get(60, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                session.close();
                client.close();
            } finally {
                proxy.close();
            }
        }
    }

    /** Answers every proxied request with a small page and records its target. */
    private static final class ForwardProxy implements AutoCloseable {

        final List<String> requests = new CopyOnWriteArrayList<>();
        private final ServerSocket server;

        ForwardProxy() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            var thread = new Thread(this::acceptLoop, "url-permission-forward-proxy");
            thread.setDaemon(true);
            thread.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    var reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String requestLine = reader.readLine();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // Headers are not needed; the request line carries the target.
                    }
                    if (requestLine != null) {
                        String[] parts = requestLine.split(" ");
                        requests.add(parts.length > 1 ? parts[1] : requestLine);
                    }
                    byte[] body = "<!doctype html><main>URL_PERMISSION_PROXY_CONTENT</main>"
                            .getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                    out.flush();
                } catch (IOException e) {
                    if (server.isClosed()) {
                        return;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
