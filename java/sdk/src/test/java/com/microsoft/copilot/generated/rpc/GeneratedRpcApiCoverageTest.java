/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.generated.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.copilot.AllowCopilotExperimental;

/**
 * Coverage tests for generated RPC API classes that are not exercised in
 * {@link RpcWrappersTest}. Uses the same {@link StubCaller} pattern to verify
 * that each API method dispatches the correct RPC method name and passes
 * parameters correctly.
 */
class GeneratedRpcApiCoverageTest {

    @Test
    void canonical_plugin_records_preserve_wire_defaults_without_team_provenance() throws Exception {
        var plugin = Plugin.builder().name("p").marketplace("corp").version("1.0.0").enabled(true).id("id").build();
        assertNull(plugin.team());
        assertEquals(new ObjectMapper().readTree("""
                {"name":"p","marketplace":"corp","version":"1.0.0","enabled":true,"id":"id"}
                """), new ObjectMapper().valueToTree(plugin));

        var installed = InstalledPluginInfo.builder().name("p").marketplace("corp").version("1.0.0").enabled(true)
                .id("id").build();
        assertNull(installed.team());
        assertEquals(new ObjectMapper().readTree("""
                {"name":"p","marketplace":"corp","version":"1.0.0","enabled":true,"id":"id"}
                """), new ObjectMapper().valueToTree(installed));
    }

    @Test
    @AllowCopilotExperimental
    void permission_requirements_preserve_builder_wire_defaults_and_provider_identity() throws Exception {
        var legacy = SessionPermissionsSetRequiredParams.builder().sessionId("session").required(true).build();
        assertNull(legacy.providerId());
        var mapper = new ObjectMapper();
        assertEquals(mapper.readTree("""
                {"sessionId":"session","required":true}
                """), mapper.valueToTree(legacy));
        var provider = SessionPermissionsSetRequiredParams.builder().sessionId("session").required(false)
                .providerId("provider").build();
        assertEquals(mapper.readTree("""
                {"sessionId":"session","required":false,"providerId":"provider"}
                """), mapper.valueToTree(provider));
    }

    @Test
    @AllowCopilotExperimental
    void managedSettings_get_and_recheck_share_result_type() throws Exception {
        var stub = new StubCaller();
        var snapshot = new ObjectMapper().readValue("{}", SessionManagedSettingsGetResult.class);
        stub.nextResult = snapshot;
        var session = new SessionRpc(stub, "policy-session");
        CompletableFuture<SessionManagedSettingsGetResult> current = session.managedSettings.get();
        CompletableFuture<SessionManagedSettingsGetResult> refreshed = session.managedSettings.recheckPolicyHelper();
        assertSame(snapshot, current.join());
        assertSame(snapshot, refreshed.join());
        assertEquals(List.of("session.managedSettings.get", "session.managedSettings.recheckPolicyHelper"),
                stub.calls.stream().map(StubCaller.Call::method).toList());
        for (var call : stub.calls) {
            assertEquals(SessionManagedSettingsGetResult.class, call.resultType());
            assertEquals(Map.of("sessionId", "policy-session"), call.params());
        }
    }

    @Test
    @AllowCopilotExperimental
    void builtin_agent_flag_overrides_uses_schema_named_result() {
        var stub = new StubCaller();
        var session = new SessionRpc(stub, "bound-session");
        session.tools.getBuiltinAgentFlagOverrides().join();

        assertEquals(1, stub.calls.size());
        var call = stub.calls.get(0);
        assertEquals("session.tools.getBuiltinAgentFlagOverrides", call.method());
        assertEquals(Map.of("sessionId", "bound-session"), call.params());
        assertEquals("ToolsGetBuiltinAgentFlagOverridesResult", call.resultType().getSimpleName());
    }

    @Test
    @AllowCopilotExperimental
    void additional_directories_list_uses_schema_named_result() {
        var stub = new StubCaller();
        var expected = PermissionsAdditionalDirectoriesListResult.builder().directories(List.of("/workspace/shared"))
                .build();
        stub.nextResult = expected;
        var session = new SessionRpc(stub, "bound-session");
        CompletableFuture<PermissionsAdditionalDirectoriesListResult> result = session.permissions.additionalDirectories
                .list();

        assertSame(expected, result.join());
        assertEquals(List.of("/workspace/shared"), result.join().directories());
        assertEquals(1, stub.calls.size());
        var call = stub.calls.get(0);
        assertEquals("session.permissions.additionalDirectories.list", call.method());
        assertEquals(Map.of("sessionId", "bound-session"), call.params());
        assertEquals(PermissionsAdditionalDirectoriesListResult.class, call.resultType());
    }

    @Test
    @AllowCopilotExperimental
    void sessions_open_preserves_builder_wire_defaults_and_construction_plan() throws Exception {
        var mapper = new ObjectMapper();
        var metadata = mapper.readValue("{\"sessionId\":\"remote\"}", RemoteSessionMetadataValue.class);
        var sessionApi = Map.of("api", "legacy");
        var startupPrompts = List.of("startup");
        var progress = List.<SessionsOpenProgress>of();
        var legacy = SessionsOpenResult.builder().status(SessionsOpenStatus.CREATED).sessionId("legacy")
                .sessionApi(sessionApi).startupPrompts(startupPrompts).remoteSessionId("remote").metadata(metadata)
                .progress(progress).build();

        assertNull(legacy.nativeConstructionPlan());
        assertEquals(mapper.readTree("""
                {"status":"created","sessionId":"legacy","sessionApi":{"api":"legacy"},
                 "startupPrompts":["startup"],"remoteSessionId":"remote",
                 "metadata":{"sessionId":"remote"},"progress":[]}
                """), mapper.valueToTree(legacy));
        assertEquals(legacy, mapper.readValue(mapper.writeValueAsString(legacy), SessionsOpenResult.class));

        var plan = Map.of("sessionId", "native");
        var withPlan = SessionsOpenResult.builder().nativeConstructionPlan(plan).status(legacy.status())
                .sessionId(legacy.sessionId()).sessionApi(legacy.sessionApi()).startupPrompts(legacy.startupPrompts())
                .remoteSessionId(legacy.remoteSessionId()).metadata(legacy.metadata()).progress(legacy.progress())
                .build();
        var expectedWithPlan = (ObjectNode) mapper.valueToTree(legacy);
        expectedWithPlan.set("nativeConstructionPlan", mapper.valueToTree(plan));
        assertEquals(expectedWithPlan, mapper.valueToTree(withPlan));
        assertEquals(withPlan, mapper.readValue(mapper.writeValueAsString(withPlan), SessionsOpenResult.class));
    }

    @Test
    @AllowCopilotExperimental
    void sessions_close_preserves_builder_omission_explicit_null_and_detach_option() throws Exception {
        var legacy = SessionsCloseParams.builder().sessionId("legacy").build();
        assertNull(legacy.detachSessionEndHooks());

        var params = List.of(legacy,
                SessionsCloseParams.builder().sessionId("explicit-null").detachSessionEndHooks(null).build(),
                SessionsCloseParams.builder().sessionId("detached").detachSessionEndHooks(true).build(),
                SessionsCloseParams.builder().sessionId("inline").detachSessionEndHooks(false).build());
        var expected = List.of("""
                {"sessionId":"legacy"}
                """, """
                {"sessionId":"explicit-null","detachSessionEndHooks":null}
                """, """
                {"sessionId":"detached","detachSessionEndHooks":true}
                """, """
                {"sessionId":"inline","detachSessionEndHooks":false}
                """);
        var stub = new StubCaller();
        var server = new ServerRpc(stub);
        for (var request : params) {
            server.sessions.close(request).join();
        }

        assertEquals(params.size(), stub.calls.size());
        var mapper = new ObjectMapper();
        for (int index = 0; index < params.size(); index++) {
            var call = stub.calls.get(index);
            assertEquals("sessions.close", call.method());
            assertEquals(Void.class, call.resultType());
            assertEquals(mapper.readTree(expected.get(index)), mapper.valueToTree(call.params()));
        }
    }

    /** A simple stub {@link RpcCaller} that records every call made to it. */
    private static final class StubCaller implements RpcCaller {

        record Call(String method, Object params, Class<?> resultType) {
        }

        final List<Call> calls = new ArrayList<>();
        Object nextResult = null;

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> invoke(String method, Object params, Class<T> resultType) {
            calls.add(new Call(method, params, resultType));
            return CompletableFuture.completedFuture((T) nextResult);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newRpcDispatches")
    void newRpcEndpoints_dispatch_expected_method_params_and_result_type(String expectedMethod,
            Class<?> expectedResultType, String expectedParams,
            BiFunction<ServerRpc, SessionRpc, CompletableFuture<?>> invoke) throws Exception {
        var stub = new StubCaller();
        invoke.apply(new ServerRpc(stub), new SessionRpc(stub, "bound-session")).join();

        assertEquals(1, stub.calls.size());
        var call = stub.calls.get(0);
        assertEquals(expectedMethod, call.method());
        assertEquals(expectedResultType, call.resultType());
        var mapper = new ObjectMapper();
        assertEquals(mapper.readTree(expectedParams), mapper.readTree(mapper.writeValueAsString(call.params())));
    }

    static Stream<Arguments> newRpcDispatches() {
        return Stream.of(dispatch("session.mcp.list", SessionMcpListResult.class, """
                {"sessionId":"bound-session"}
                """, (server, session) -> session.mcp.list()),
                dispatch("session.mcp.listConfigured", SessionMcpListConfiguredResult.class, """
                        {"sessionId":"bound-session"}
                        """, (server, session) -> session.mcp.listConfigured()),
                dispatch("session.mcp.prompts.list", SessionMcpPromptsListResult.class, """
                        {"sessionId":"bound-session","serverName":"fixture","cursor":"page:2/opaque+cursor="}
                        """, (server, session) -> session.mcp.prompts.list(SessionMcpPromptsListParams.builder()
                        .sessionId("foreign-session").serverName("fixture").cursor("page:2/opaque+cursor=").build())),
                dispatch("session.mcp.prompts.get", SessionMcpPromptsGetResult.class, """
                        {"sessionId":"bound-session","serverName":"fixture","promptName":"rich",
                         "arguments":{"topic":"日本語","style":""}}
                        """,
                        (server, session) -> session.mcp.prompts.get(
                                SessionMcpPromptsGetParams.builder().sessionId("foreign-session").serverName("fixture")
                                        .promptName("rich").arguments(Map.of("topic", "日本語", "style", "")).build())),
                dispatch("catalog.select", CatalogSelectionResult.class, """
                        {"contract":{"protocolVersion":3,"requiredCapabilities":["catalog-selection"]},
                         "sessionId":"catalog-session","selectionRef":"selection-1","outcome":"selected"}
                        """,
                        (server, session) -> server.catalog.select(CatalogSelectParams.builder()
                                .contract(CatalogClientContract.builder().protocolVersion(3L)
                                        .requiredCapabilities(List.of("catalog-selection")).build())
                                .sessionId("catalog-session").selectionRef("selection-1")
                                .outcome(CatalogSelectionDecision.SELECTED).build())),
                dispatch("session.managedSettings.get", SessionManagedSettingsGetResult.class, """
                        {"sessionId":"bound-session"}
                        """, (server, session) -> session.managedSettings.get()),
                dispatch("session.instructions.reload", SessionInstructionsGetSourcesResult.class, """
                        {"sessionId":"bound-session"}
                        """, (server, session) -> session.instructions.reload()),
                dispatch("session.customizations.reload", CustomizationsReloadResult.class, """
                        {"sessionId":"bound-session"}
                        """, (server, session) -> session.customizations.reload()),
                dispatch("session.plugins.install", SessionPluginsInstallResult.class, """
                        {"sessionId":"bound-session","source":"plugin@market"}
                        """,
                        (server, session) -> session.plugins.install(SessionPluginsInstallParams.builder()
                                .sessionId("foreign-session").source("plugin@market").build())),
                dispatch("session.plugins.uninstall", Void.class, """
                        {"sessionId":"bound-session","name":"plugin","directSourceId":"source-1"}
                        """,
                        (server, session) -> session.plugins.uninstall(SessionPluginsUninstallParams.builder()
                                .sessionId("foreign-session").name("plugin").directSourceId("source-1").build())),
                dispatch("session.plugins.update", SessionPluginsUpdateResult.class, """
                        {"sessionId":"bound-session","name":"plugin@market"}
                        """,
                        (server, session) -> session.plugins.update(SessionPluginsUpdateParams.builder()
                                .sessionId("foreign-session").name("plugin@market").build())),
                dispatch("session.plugins.enable", Void.class, """
                        {"sessionId":"bound-session","names":["first@market","second@market"]}
                        """,
                        (server, session) -> session.plugins.enable(SessionPluginsEnableParams.builder()
                                .sessionId("foreign-session").names(List.of("first@market", "second@market")).build())),
                dispatch("session.plugins.disable", Void.class, """
                        {"sessionId":"bound-session","names":["plugin@market"]}
                        """,
                        (server, session) -> session.plugins.disable(SessionPluginsDisableParams.builder()
                                .sessionId("foreign-session").names(List.of("plugin@market")).build())),
                dispatch("session.plugins.marketplaces.list", SessionPluginsMarketplacesListResult.class, """
                        {"sessionId":"bound-session"}
                        """, (server, session) -> session.plugins.marketplaces.list()),
                dispatch("session.plugins.marketplaces.add", SessionPluginsMarketplacesAddResult.class, """
                        {"sessionId":"bound-session","source":"./market","workingDirectory":"/workspace"}
                        """,
                        (server, session) -> session.plugins.marketplaces
                                .add(SessionPluginsMarketplacesAddParams.builder().sessionId("foreign-session")
                                        .source("./market").workingDirectory("/workspace").build())),
                dispatch("session.plugins.marketplaces.remove", SessionPluginsMarketplacesRemoveResult.class, """
                        {"sessionId":"bound-session","name":"market","force":true}
                        """,
                        (server, session) -> session.plugins.marketplaces.remove(SessionPluginsMarketplacesRemoveParams
                                .builder().sessionId("foreign-session").name("market").force(true).build())),
                dispatch("session.plugins.marketplaces.browse", SessionPluginsMarketplacesBrowseResult.class, """
                        {"sessionId":"bound-session","name":"market"}
                        """,
                        (server, session) -> session.plugins.marketplaces.browse(SessionPluginsMarketplacesBrowseParams
                                .builder().sessionId("foreign-session").name("market").build())),
                dispatch("session.plugins.marketplaces.refresh", SessionPluginsMarketplacesRefreshResult.class, """
                        {"sessionId":"bound-session","name":"market"}
                        """, (server,
                        session) -> session.plugins.marketplaces.refresh(SessionPluginsMarketplacesRefreshParams
                                .builder().sessionId("foreign-session").name("market").build())),
                dispatch("session.plugins.marketplaces.refresh", SessionPluginsMarketplacesRefreshResult.class, """
                        {"sessionId":"bound-session"}
                        """, (server, session) -> session.plugins.marketplaces.refresh(null)),
                dispatch("session.queue.withdrawMessage", SessionQueueWithdrawMessageResult.class, """
                        {"sessionId":"bound-session","messageId":"message-1","expectedPrompt":"original"}
                        """,
                        (server, session) -> session.queue.withdrawMessage(
                                SessionQueueWithdrawMessageParams.builder().sessionId("foreign-session")
                                        .messageId("message-1").expectedPrompt("original").build())),
                dispatch("session.queue.appendSteering", SessionQueueAppendSteeringResult.class, """
                        {"sessionId":"bound-session","messageId":"message-1","expectedPrompt":"original",
                         "agentMode":"interactive","prompt":"more","displayPrompt":"preview","attachments":[]}
                        """,
                        (server, session) -> session.queue.appendSteering(SessionQueueAppendSteeringParams.builder()
                                .sessionId("foreign-session").messageId("message-1").expectedPrompt("original")
                                .agentMode(SendAgentMode.INTERACTIVE).prompt("more").displayPrompt("preview")
                                .attachments(List.of()).build())),
                dispatch("session.workflow.run", SessionWorkflowRunResult.class, """
                        {"sessionId":"bound-session","name":"workflow-1","args":{"topic":"input"}}
                        """, (server,
                        session) -> session.workflow.run(SessionWorkflowRunParams.builder().sessionId("foreign-session")
                                .name("workflow-1").args(Map.of("topic", "input")).options(null).build())),
                dispatch("session.workflow.resume", SessionWorkflowResumeResult.class, """
                        {"sessionId":"bound-session","runId":"run-1","notifyOnComplete":true,"logPhaseNames":false}
                        """,
                        (server, session) -> session.workflow.resume(
                                SessionWorkflowResumeParams.builder().sessionId("foreign-session").runId("run-1")
                                        .limits(null).notifyOnComplete(true).logPhaseNames(false).build())),
                dispatch("session.workflow.runFromTool", WorkflowRunResult.class, """
                        {"sessionId":"bound-session","name":"workflow-1","args":{"topic":"input"},"toolCallId":"tool-1"}
                        """,
                        (server, session) -> session.workflow.runFromTool(SessionWorkflowRunFromToolParams.builder()
                                .sessionId("foreign-session").name("workflow-1").args(Map.of("topic", "input"))
                                .options(null).toolCallId("tool-1").build())),
                dispatch("session.workflow.resumeFromTool", WorkflowResumeResult.class, """
                        {"sessionId":"bound-session","runId":"run-1","toolCallId":"tool-1"}
                        """,
                        (server, session) -> session.workflow.resumeFromTool(
                                SessionWorkflowResumeFromToolParams.builder().sessionId("foreign-session")
                                        .runId("run-1").limits(null).toolCallId("tool-1").build())),
                dispatch("session.workflow.getRun", SessionWorkflowGetRunResult.class, """
                        {"sessionId":"bound-session","runId":"run-1"}
                        """,
                        (server, session) -> session.workflow.getRun(SessionWorkflowGetRunParams.builder()
                                .sessionId("foreign-session").runId("run-1").build())),
                dispatch("session.workflow.listRuns", SessionWorkflowListRunsResult.class, """
                        {"sessionId":"bound-session","afterSeq":2,"limit":5}
                        """,
                        (server, session) -> session.workflow.listRuns(SessionWorkflowListRunsParams.builder()
                                .sessionId("foreign-session").afterSeq(2L).beforeSeq(null).limit(5L).build())),
                dispatch("session.workflow.getRunDetail", SessionWorkflowGetRunDetailResult.class, """
                        {"sessionId":"bound-session","runId":"run-1"}
                        """,
                        (server, session) -> session.workflow.getRunDetail(SessionWorkflowGetRunDetailParams.builder()
                                .sessionId("foreign-session").runId("run-1").build())),
                dispatch("session.workflow.getRunProgress", SessionWorkflowGetRunProgressResult.class, """
                        {"sessionId":"bound-session","runId":"run-1","phaseId":"phase-1","afterSeq":2,"limit":5}
                        """,
                        (server, session) -> session.workflow.getRunProgress(SessionWorkflowGetRunProgressParams
                                .builder().sessionId("foreign-session").runId("run-1").phaseId("phase-1").afterSeq(2L)
                                .beforeSeq(null).limit(5L).build())),
                dispatch("session.workflow.cancel", SessionWorkflowCancelResult.class, """
                        {"sessionId":"bound-session","runId":"run-1"}
                        """,
                        (server, session) -> session.workflow.cancel(SessionWorkflowCancelParams.builder()
                                .sessionId("foreign-session").runId("run-1").build())),
                dispatch("session.workflow.pause", SessionWorkflowPauseResult.class, """
                        {"sessionId":"bound-session","runId":"run-1"}
                        """,
                        (server, session) -> session.workflow.pause(SessionWorkflowPauseParams.builder()
                                .sessionId("foreign-session").runId("run-1").build())),
                dispatch("session.workflow.pauseAtCheckpoint", WorkflowPauseCheckpointResult.class,
                        """
                                {"sessionId":"bound-session","runId":"run-1","executionToken":"execution-1","key":"checkpoint-1"}
                                """,
                        (server, session) -> session.workflow.pauseAtCheckpoint(
                                SessionWorkflowPauseAtCheckpointParams.builder().sessionId("foreign-session")
                                        .runId("run-1").executionToken("execution-1").key("checkpoint-1").build())),
                dispatch("session.workflow.log", Void.class, """
                        {"sessionId":"bound-session","runId":"run-1","executionToken":"execution-1",
                         "lines":[{"seq":1,"kind":"log","text":"progress"}]}
                        """,
                        (server, session) -> session.workflow.log(SessionWorkflowLogParams.builder()
                                .sessionId("foreign-session").runId("run-1").executionToken("execution-1")
                                .lines(List.of(WorkflowLogLine.builder().seq(1L).kind(WorkflowLogLineKind.LOG)
                                        .text("progress").build()))
                                .build())),
                dispatch("session.workflow.agent", SessionWorkflowAgentResult.class,
                        """
                                {"sessionId":"bound-session","workflowRunId":"run-1","executionToken":"execution-1","prompt":"inspect","opts":{}}
                                        """,
                        (server, session) -> session.workflow
                                .agent(SessionWorkflowAgentParams.builder().sessionId("foreign-session")
                                        .workflowRunId("run-1").executionToken("execution-1").prompt("inspect")
                                        .opts(com.microsoft.copilot.generated.rpc.WorkflowAgentOptions.builder()
                                                .build())
                                        .build())),
                dispatch("session.workflow.journal.get", SessionWorkflowJournalGetResult.class, """
                        {"sessionId":"bound-session","runId":"run-1","executionToken":"execution-1","key":"journal-1"}
                        """,
                        (server, session) -> session.workflow.journal
                                .get(SessionWorkflowJournalGetParams.builder().sessionId("foreign-session")
                                        .runId("run-1").executionToken("execution-1").key("journal-1").build())),
                dispatch("session.workflow.journal.put", Void.class, """
                        {"sessionId":"bound-session","runId":"run-1","executionToken":"execution-1","key":"journal-1",
                         "resultJson":{"answer":42}}
                        """,
                        (server, session) -> session.workflow.journal.put(SessionWorkflowJournalPutParams.builder()
                                .sessionId("foreign-session").runId("run-1").executionToken("execution-1")
                                .key("journal-1").resultJson(Map.of("answer", 42)).build())));
    }

    private static Arguments dispatch(String method, Class<?> resultType, String expectedParams,
            BiFunction<ServerRpc, SessionRpc, CompletableFuture<?>> invoke) {
        return Arguments.of(method, resultType, expectedParams, invoke);
    }

    // ── SessionRpc.model ───────────────────────────────────────────────────

    @Test
    void sessionRpc_model_setAllowedModels_merges_sessionId_and_returns_result() {
        var stub = new StubCaller();
        var session = new SessionRpc(stub, "sess-model-policy");
        var allowedModels = List.of("gpt-5", "azure/gpt-5");
        var expectedResult = SessionModelSetAllowedModelsResult.builder().allowedModels(allowedModels)
                .effectiveAllowedModels(List.of("gpt-5")).fallbackModel("gpt-5").modelId("gpt-5").build();
        stub.nextResult = expectedResult;
        var request = SessionModelSetAllowedModelsParams.builder().sessionId("ignored-session")
                .allowedModels(allowedModels).build();

        assertSame(expectedResult, session.model.setAllowedModels(request).join());

        assertEquals(1, stub.calls.size());
        var call = stub.calls.get(0);
        assertEquals("session.model.setAllowedModels", call.method());
        assertEquals(SessionModelSetAllowedModelsResult.class, call.resultType());
        var params = assertInstanceOf(ObjectNode.class, call.params());
        assertEquals("sess-model-policy", params.get("sessionId").asText());
        assertEquals(2, params.get("allowedModels").size());
        assertEquals("gpt-5", params.get("allowedModels").get(0).asText());
        assertEquals("azure/gpt-5", params.get("allowedModels").get(1).asText());
        assertEquals("ignored-session", request.sessionId());
    }

    @Test
    void sessionRpc_model_setAllowedModels_sends_null_to_clear_host_restriction() {
        var stub = new StubCaller();
        var session = new SessionRpc(stub, "sess-model-policy");

        session.model
                .setAllowedModels(
                        SessionModelSetAllowedModelsParams.builder().sessionId(null).allowedModels(null).build())
                .join();

        assertEquals(1, stub.calls.size());
        assertEquals("session.model.setAllowedModels", stub.calls.get(0).method());
        var params = assertInstanceOf(ObjectNode.class, stub.calls.get(0).params());
        assertEquals("sess-model-policy", params.get("sessionId").asText());
        assertTrue(params.has("allowedModels"));
        assertTrue(params.get("allowedModels").isNull());
    }

    @Test
    void sessionRpc_model_setAllowedModels_omits_untouched_host_restriction() {
        var stub = new StubCaller();
        var session = new SessionRpc(stub, "sess-model-policy");

        session.model.setAllowedModels(SessionModelSetAllowedModelsParams.builder().build()).join();

        assertEquals(1, stub.calls.size());
        assertEquals("session.model.setAllowedModels", stub.calls.get(0).method());
        var params = assertInstanceOf(ObjectNode.class, stub.calls.get(0).params());
        assertEquals("sess-model-policy", params.get("sessionId").asText());
        assertFalse(params.has("allowedModels"));
    }

    @Test
    void sessionRpc_model_setAllowedModels_preserves_explicit_empty_list() {
        var stub = new StubCaller();
        var session = new SessionRpc(stub, "sess-model-policy");

        session.model
                .setAllowedModels(
                        SessionModelSetAllowedModelsParams.builder().sessionId(null).allowedModels(List.of()).build())
                .join();

        assertEquals(1, stub.calls.size());
        assertEquals("session.model.setAllowedModels", stub.calls.get(0).method());
        var params = assertInstanceOf(ObjectNode.class, stub.calls.get(0).params());
        assertEquals("sess-model-policy", params.get("sessionId").asText());
        assertTrue(params.get("allowedModels").isArray());
        assertTrue(params.get("allowedModels").isEmpty());
    }

    // ── SessionLogParams enum ──────────────────────────────────────────────

    @Test
    void sessionLogParams_level_enum_fromValue_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> SessionLogLevel.fromValue("unknown-level"));
    }
}
