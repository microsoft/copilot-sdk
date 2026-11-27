/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.generated.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.AllowCopilotExperimental;
import com.microsoft.copilot.generated.PolicyHelperCapabilities;
import com.microsoft.copilot.generated.PolicyHelperFailure;
import com.microsoft.copilot.generated.PolicyHelperFailureDetail;
import com.microsoft.copilot.generated.PolicyHelperHelpLink;
import com.microsoft.copilot.generated.SessionManagedSettingsResolvedEvent.SessionManagedSettingsResolvedEventData;

/**
 * Wire-shape tests for generated RPC param and result record types: JSON field
 * names, required-null versus omitted values, polymorphic subtypes and
 * extension preservation.
 */
class GeneratedRpcRecordsCoverageTest {

    @ParameterizedTest
    @ValueSource(strings = {"form", "url"})
    void elicitationPromptPreservesMcpExtensions(String mode) throws Exception {
        var mapper = new ObjectMapper();
        var wire = mapper.createObjectNode().put("mode", mode).put("message", "MCP request");
        if (mode.equals("form")) {
            wire.set("requestedSchema", mapper.readTree("""
                    {"type":"object","properties":{}}
                    """));
        } else {
            wire.put("url", "https://example.com/authorize").put("elicitationId", "browser-request");
        }
        wire.set("x-vendor", mapper.readTree("""
                {"nested":[{"enabled":true},null,42],"label":"extension"}
                """));
        wire.putNull("x-null");
        wire.put("x-count", 3);

        var prompt = mapper.treeToValue(wire, UIElicitationPrompt.class);
        if (mode.equals("form")) {
            assertInstanceOf(UIElicitationPromptForm.class, prompt);
        } else {
            assertInstanceOf(UIElicitationPromptUrl.class, prompt);
        }
        assertEquals(wire, mapper.valueToTree(prompt));
    }

    static Stream<Arguments> customizationBuilders() {
        return Stream.of(
                Arguments.of(
                        AgentInfo.builder().name("agent").displayName("Agent").description("Description")
                                .path("/agent.md").id("agent-id").userInvocable(true).disableModelInvocation(false)
                                .tools(List.of("view")).model("model").prompt("Prompt").build(),
                        """
                                {"name":"agent","displayName":"Agent","description":"Description","path":"/agent.md",
                                 "id":"agent-id","userInvocable":true,"disableModelInvocation":false,
                                 "tools":["view"],"model":"model","prompt":"Prompt"}
                                """, "{\"pluginId\":\"builtin:tools\"}"),
                Arguments.of(DiscoveredExtensionPlugin.builder().name("tools").build(), "{\"name\":\"tools\"}",
                        "{\"id\":\"builtin:tools\"}"),
                Arguments.of(DiscoveredHook.builder().id("hook").hookType(HookType.PRETOOLUSE).origin(HookOrigin.PLUGIN)
                        .source("tools").projectPath("/project").enabled(true).disableKey("disable-hook").build(), """
                                {"id":"hook","hookType":"preToolUse","origin":"plugin","source":"tools",
                                 "projectPath":"/project","enabled":true,"disableKey":"disable-hook"}
                                """, "{\"pluginId\":\"builtin:tools\",\"definitionFileUri\":\"file:///hooks.json\"}"),
                Arguments.of(InstalledPluginInfo.builder().name("tools").marketplace("catalog").version("1.0")
                        .enabled(true).installedFrom("/catalog").source("source").managed(false).installed(true)
                        .description("Description").iconUrl("https://example.com/icon").build(), """
                                {"name":"tools","marketplace":"catalog","version":"1.0","enabled":true,
                                 "installedFrom":"/catalog","source":"source","managed":false,"installed":true,
                                 "description":"Description","iconUrl":"https://example.com/icon"}
                                """, "{\"id\":\"mp:catalog::tools\",\"rootPath\":\"/plugins/tools\"}"),
                Arguments.of(
                        InstructionSource.builder().id("instruction").label("Instructions")
                                .sourcePath("/instructions.md").content("Content").type(InstructionSourceType.PLUGIN)
                                .location(InstructionSourceLocation.PLUGIN).applyTo(List.of("*.ts"))
                                .description("Description").defaultDisabled(false).projectPath("/project").build(),
                        """
                                {"id":"instruction","label":"Instructions","sourcePath":"/instructions.md","content":"Content",
                                 "type":"plugin","location":"plugin","applyTo":["*.ts"],"description":"Description",
                                 "defaultDisabled":false,"projectPath":"/project"}
                                """,
                        "{\"pluginId\":\"builtin:tools\"}"),
                Arguments.of(Plugin.builder().name("tools").marketplace("catalog").version("1.0").enabled(true)
                        .installedFrom("/catalog").source("source").managed(false).installed(true).build(), """
                                {"name":"tools","marketplace":"catalog","version":"1.0","enabled":true,
                                 "installedFrom":"/catalog","source":"source","managed":false,"installed":true}
                                """, "{\"id\":\"mp:catalog::tools\",\"rootPath\":\"/plugins/tools\"}"),
                Arguments.of(ServerSkill.builder().name("skill").commandName("command").description("Description")
                        .source(SkillSource.PLUGIN).userInvocable(true).enabled(true).path("/SKILL.md")
                        .projectPath("/project").argumentHint("argument").build(), """
                                {"name":"skill","commandName":"command","description":"Description","source":"plugin",
                                 "userInvocable":true,"enabled":true,"path":"/SKILL.md","projectPath":"/project",
                                 "argumentHint":"argument"}
                                """, "{\"pluginId\":\"builtin:tools\"}"),
                Arguments.of(
                        Skill.builder().name("skill").declaredName("declared").commandName("command")
                                .description("Description").source(SkillSource.PLUGIN).userInvocable(true).enabled(true)
                                .path("/SKILL.md").pluginName("tools").argumentHint("argument").build(),
                        """
                                {"name":"skill","declaredName":"declared","commandName":"command","description":"Description",
                                 "source":"plugin","userInvocable":true,"enabled":true,"path":"/SKILL.md",
                                 "pluginName":"tools","argumentHint":"argument"}
                                """,
                        "{\"pluginId\":\"builtin:tools\"}"));
    }

    @ParameterizedTest
    @MethodSource("customizationBuilders")
    void customization_builders_preserve_wire_defaults_and_round_trip_metadata(Object legacy, String expectedJson,
            String metadataJson) throws Exception {
        var mapper = new ObjectMapper();
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(expectedJson);
        assertEquals(expected, mapper.valueToTree(legacy));
        assertEquals(legacy, mapper.treeToValue(expected, legacy.getClass()));
        expected.setAll((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(metadataJson));
        assertEquals(expected, mapper.valueToTree(mapper.treeToValue(expected, legacy.getClass())));
    }

    @Test
    @AllowCopilotExperimental
    void skills_discovery_preserves_released_constructor_and_wire_fields() throws Exception {
        var constructor = SkillsDiscoverParams.class.getConstructor(List.class, List.class, List.class, Boolean.class);
        var params = constructor.newInstance(List.of("/project"), List.of("/skills"), List.of("/ignored"), true);
        var mapper = new ObjectMapper();
        assertEquals(mapper.readTree("""
                {"projectPaths":["/project"],"skillDirectories":["/skills"],
                 "ignoredSkillsLocations":["/ignored"],"excludeHostSkills":true}
                """), mapper.valueToTree(params));
    }

    @Test
    @AllowCopilotExperimental
    void skills_discovery_paths_preserves_released_constructor_and_wire_fields() throws Exception {
        var constructor = SkillsGetDiscoveryPathsParams.class.getConstructor(List.class, List.class, List.class,
                Boolean.class);
        var params = constructor.newInstance(List.of("/skills"), List.of("/project"), List.of("/ignored"), true);
        var mapper = new ObjectMapper();
        assertEquals(mapper.readTree("""
                {"skillDirectories":["/skills"],"projectPaths":["/project"],
                 "ignoredSkillsLocations":["/ignored"],"excludeHostSkills":true}
                """), mapper.valueToTree(params));
    }

    @Test
    void localModelMachineRow_preserves_typed_state_and_resource_requirements() throws Exception {
        var mapper = new ObjectMapper();
        var expected = mapper.readTree("""
                {"status":{"kind":"downloading","receivedBytes":37.0,"totalBytes":100.0,"stage":"download"},
                 "disk":{"kind":"insufficient","freeBytes":2.0,"requiredBytes":4.0,"directory":"models"},
                 "gpu":{"kind":"insufficient","availableBytes":8.0,"requiredBytes":16.0,"description":"Adapter"}}
                """);
        var row = mapper.treeToValue(expected, LocalModelMachineRow.class);
        var status = assertInstanceOf(LocalModelStatusDownloading.class, row.status());
        var disk = assertInstanceOf(LocalModelDiskRequirementInsufficient.class, row.disk());
        var gpu = assertInstanceOf(LocalModelGpuRequirementInsufficient.class, row.gpu());
        assertEquals(37.0, status.getReceivedBytes());
        assertEquals(100.0, status.getTotalBytes());
        assertEquals("download", status.getStage());
        assertEquals(2.0, disk.getFreeBytes());
        assertEquals(4.0, disk.getRequiredBytes());
        assertEquals("models", disk.getDirectory());
        assertEquals(8.0, gpu.getAvailableBytes());
        assertEquals(16.0, gpu.getRequiredBytes());
        assertEquals("Adapter", gpu.getDescription());
        var actual = mapper.valueToTree(row);
        for (String field : List.of("status", "disk", "gpu")) {
            assertEquals(expected.get(field), actual.get(field));
        }
    }

    @Test
    void ignoredScriptSafetyAccessorsAreRemoved() throws Exception {
        for (var recordType : List.of(SessionOpenOptions.class, SessionOptionsUpdateParams.class)) {
            assertThrows(NoSuchMethodException.class, () -> recordType.getMethod("enableScriptSafety"),
                    () -> recordType.getSimpleName() + ".enableScriptSafety must be removed");
            var shellOwner = recordType.getMethod("tools").getReturnType();
            assertFalse(shellOwner.getMethod("shell").isAnnotationPresent(Deprecated.class),
                    () -> recordType.getSimpleName() + ".tools.shell must remain supported");
        }
    }

    @Test
    @AllowCopilotExperimental
    void policyHelper_snapshots_and_events_share_dto_types() throws Exception {
        var mapper = new ObjectMapper();
        var json = mapper.readTree("""
                {"policyHelperFailure":{"category":"helper-reported","failureMode":"ignore",
                "severity":"warning","reason":"sessionStart","previousPolicyRetained":false,
                "message":"Invalid configuration","details":[{"path":"setting","message":"Invalid value"}],
                "helpLink":{"url":"https://example.com/help"}},
                "policyHelperCapabilities":{"structuredFailure":true,"recheck":true}}
                """);
        var event = mapper.treeToValue(json, SessionManagedSettingsResolvedEventData.class);
        var snapshot = mapper.treeToValue(json, ManagedSettingsResolvedData.class);
        var get = mapper.treeToValue(json, SessionManagedSettingsGetResult.class);
        var recheck = mapper.treeToValue(json, SessionManagedSettingsGetResult.class);
        List<PolicyHelperFailure> failures = List.of(event.policyHelperFailure(), snapshot.policyHelperFailure(),
                get.policyHelperFailure(), recheck.policyHelperFailure());
        List<PolicyHelperCapabilities> capabilities = List.of(event.policyHelperCapabilities(),
                snapshot.policyHelperCapabilities(), get.policyHelperCapabilities(),
                recheck.policyHelperCapabilities());
        for (PolicyHelperFailure failure : failures) {
            assertEquals(event.policyHelperFailure(), failure);
            PolicyHelperFailureDetail detail = failure.details().get(0);
            PolicyHelperHelpLink link = failure.helpLink();
            assertEquals("setting", detail.path());
            assertEquals("https://example.com/help", link.url());
        }
        for (PolicyHelperCapabilities capability : capabilities) {
            assertEquals(event.policyHelperCapabilities(), capability);
        }
    }

    @Test
    @AllowCopilotExperimental
    void managedSettingsResolve_builders_preserve_wire_defaults() throws Exception {
        var params = ManagedSettingsResolveParams.builder().selectionId("account").clientName("app")
                .workingDirectory("/workspace").build();
        assertEquals("account", params.selectionId());
        assertEquals("app", params.clientName());
        assertEquals("/workspace", params.workingDirectory());
        var mapper = new ObjectMapper();
        var json = mapper.valueToTree(params);
        assertEquals("/workspace", json.get("workingDirectory").asText());
        assertFalse(json.has("policyHelperReason"));
        assertEquals(params, mapper.treeToValue(json, ManagedSettingsResolveParams.class));
        var withoutDirectory = ManagedSettingsResolveParams.builder().selectionId("account").clientName("app").build();
        assertNull(withoutDirectory.workingDirectory());
    }

    @Test
    @AllowCopilotExperimental
    void managedPermissions_reuses_event_context_and_evaluated_operations() throws Exception {
        var mapper = new ObjectMapper();
        var eventJson = mapper.readTree("""
                {"permissionsContext":{"permissions":{"_limitToLists":[["https://example.com"]]},"failClosed":false}}
                """);
        var eventData = mapper.treeToValue(eventJson, SessionManagedSettingsResolvedEventData.class);
        var evaluation = mapper.readValue("""
                {"operation":{"kind":"url","url":"https://example.com/a"},"verdict":"allow"}
                """, ManagedPermissionEvaluation.class);
        var params = ManagedSettingsPermissionsEvaluateParams.builder().context(eventData.permissionsContext())
                .operations(List.of(evaluation.operation())).build();
        var paramsJson = mapper.valueToTree(params);
        assertEquals(eventJson.get("permissionsContext"), paramsJson.get("context"));
        assertEquals("url", paramsJson.get("operations").get(0).get("kind").asText());
        assertEquals("https://example.com/a", paramsJson.get("operations").get(0).get("url").asText());
        assertEquals(ManagedPermissionOperation.ManagedPermissionOperationKind.URL, evaluation.operation().kind());
        assertEquals(params, mapper.treeToValue(paramsJson, ManagedSettingsPermissionsEvaluateParams.class));
    }

    // ── Params records ─────────────────────────────────────────────────────

    @Test
    @AllowCopilotExperimental
    void sessionEventLogRead_builder_preserves_wire_fields() throws Exception {
        var mapper = new ObjectMapper();
        var params = SessionEventLogReadParams.builder().sessionId("session").cursor("cursor").max(100L).waitMs(1234L)
                .types(List.of("user.message")).agentScope(EventsAgentScope.PRIMARY).agentIds(List.of("agent"))
                .direction(EventsReadDirection.BACKWARD).includeEphemeral(false).build();
        var expected = mapper.readTree("""
                {"sessionId":"session","cursor":"cursor","max":100,"waitMs":1234,
                 "types":["user.message"],"agentScope":"primary","agentIds":["agent"],
                 "direction":"backward","includeEphemeral":false}
                """);

        assertEquals(expected, mapper.readTree(mapper.writeValueAsString(params)));
        assertEquals(params, mapper.treeToValue(expected, SessionEventLogReadParams.class));
    }

    @Test
    void sessionFsSetProviderCapabilities_builder_preserves_binary_wire_field() throws Exception {
        var mapper = new ObjectMapper();
        var legacy = SessionFsSetProviderCapabilities.builder().sqlite(true).build();
        var legacyJson = mapper.readTree(mapper.writeValueAsString(legacy));
        assertTrue(legacyJson.get("sqlite").asBoolean());
        assertFalse(legacyJson.has("binary"));
        assertEquals(legacy, mapper.treeToValue(legacyJson, SessionFsSetProviderCapabilities.class));

        var binary = SessionFsSetProviderCapabilities.builder().sqlite(false).binary(true).build();
        var binaryJson = mapper.readTree(mapper.writeValueAsString(binary));
        assertFalse(binaryJson.get("sqlite").asBoolean());
        assertTrue(binaryJson.get("binary").asBoolean());
        assertEquals(binary, mapper.treeToValue(binaryJson, SessionFsSetProviderCapabilities.class));
    }

    @Test
    void sessionModelSetAllowedModelsParams_distinguishes_clearing_from_empty_list() throws Exception {
        var mapper = new ObjectMapper();
        var cleared = SessionModelSetAllowedModelsParams.builder().sessionId("sess-model-policy").allowedModels(null)
                .build();

        var untouched = SessionModelSetAllowedModelsParams.builder().sessionId("sess-model-policy").build();
        assertEquals(untouched, mapper.readValue("""
                {"sessionId":"sess-model-policy"}
                """, SessionModelSetAllowedModelsParams.class));
        assertEquals(cleared, mapper.readValue("""
                {"sessionId":"sess-model-policy","allowedModels":null}
                """, SessionModelSetAllowedModelsParams.class));
        assertNotEquals(untouched, cleared);
        var clearedJson = mapper.readTree(mapper.writeValueAsString(cleared));
        assertTrue(clearedJson.has("allowedModels"));
        assertTrue(clearedJson.get("allowedModels").isNull());
        assertFalse(mapper.valueToTree(untouched).has("allowedModels"));

        var empty = SessionModelSetAllowedModelsParams.builder().sessionId("sess-model-policy").allowedModels(List.of())
                .build();
        var emptyJson = mapper.readTree(mapper.writeValueAsString(empty));
        assertTrue(emptyJson.get("allowedModels").isArray());
        assertTrue(emptyJson.get("allowedModels").isEmpty());
        assertEquals(empty, mapper.treeToValue(emptyJson, SessionModelSetAllowedModelsParams.class));
    }

    @Test
    void sessionModelSetAllowedModelsResult_all_fields_are_optional() throws Exception {
        var mapper = new ObjectMapper();
        var empty = mapper.readValue("{}", SessionModelSetAllowedModelsResult.class);
        assertNull(empty.allowedModels());
        assertNull(empty.effectiveAllowedModels());
        assertNull(empty.fallbackModel());
        assertNull(empty.modelId());

        for (var json : List.of("{}", """
                {"modelId":"gpt-5"}
                """, """
                {"allowedModels":["gpt-5"]}
                """, """
                {"effectiveAllowedModels":[],"fallbackModel":"gpt-5"}
                """)) {
            var result = mapper.readValue(json, SessionModelSetAllowedModelsResult.class);
            assertEquals(mapper.readTree(json), mapper.valueToTree(result));
        }
    }

    @Test
    void sessionModelSwitchParams_distinguishRequiredNullFromOmittedOptionalValue() {
        var mapper = new ObjectMapper();
        var switchAutoTier = mapper.valueToTree(
                SessionModelSwitchAutoTierParams.builder().sessionId("sess-32").autoTier(null).source(null).build());
        assertTrue(switchAutoTier.has("autoTier"));
        assertTrue(switchAutoTier.get("autoTier").isNull());

        var switchTo = mapper
                .valueToTree(SessionModelSwitchToParams.builder().sessionId("sess-32").modelId("auto").build());
        assertFalse(switchTo.has("autoTier"));
        assertFalse(switchTo.has("providerId"));
    }

    // ── Result records ─────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("catalogSearchSharedErrors")
    void catalogSearchResult_shared_errors_round_trip(Class<? extends CatalogSearchResult> subtype, String json)
            throws Exception {
        assertSame(CatalogSearchResult.class, subtype.getSuperclass());
        assertCatalogErrorRoundTrip(CatalogSearchResult.class, subtype, json, CatalogSearchResult::getKind);
    }

    static Stream<Arguments> catalogSearchSharedErrors() {
        return Stream.of(Arguments.of(CatalogNegotiationRefusedError.class, catalogNegotiationRefusedJson()),
                Arguments.of(CatalogInvalidRequestError.class, """
                        {"kind":"invalid-request","field":"query","message":"A query is required"}
                        """), Arguments.of(CatalogUnavailableError.class, """
                        {"kind":"unavailable","reason":"search-unavailable","message":"Search is unavailable"}
                        """));
    }

    @ParameterizedTest
    @MethodSource("catalogSelectionSharedErrors")
    void catalogSelectionResult_shared_errors_round_trip(Class<? extends CatalogSelectionResult> subtype, String json)
            throws Exception {
        assertCatalogErrorRoundTrip(CatalogSelectionResult.class, subtype, json, CatalogSelectionResult::getKind);
    }

    static Stream<Arguments> catalogSelectionSharedErrors() {
        return Stream.of(
                Arguments.of(CatalogSelectionResultCatalogNegotiationRefusedError.class,
                        catalogNegotiationRefusedJson()),
                Arguments.of(CatalogSelectionResultCatalogInvalidRequestError.class, """
                        {"kind":"invalid-request","field":"selectionRef","message":"A selection reference is required"}
                        """), Arguments.of(CatalogSelectionResultCatalogUnavailableError.class, """
                        {"kind":"unavailable","reason":"selection-unavailable","message":"Selection is unavailable"}
                        """));
    }

    @ParameterizedTest
    @MethodSource("mcpPlanInstallSharedErrors")
    void mcpPlanInstallResult_shared_errors_round_trip(Class<? extends McpPlanInstallResult> subtype, String json)
            throws Exception {
        assertCatalogErrorRoundTrip(McpPlanInstallResult.class, subtype, json, McpPlanInstallResult::getKind);
    }

    static Stream<Arguments> mcpPlanInstallSharedErrors() {
        return Stream.of(
                Arguments.of(McpPlanInstallResultCatalogNegotiationRefusedError.class, catalogNegotiationRefusedJson()),
                Arguments.of(McpPlanInstallResultCatalogInvalidRequestError.class, """
                        {"kind":"invalid-request","field":"card","message":"A card is required"}
                        """), Arguments.of(McpPlanInstallResultCatalogAuthenticationRequiredError.class, """
                        {"kind":"authentication-required","reason":"credential-expired","message":"Sign in again"}
                        """), Arguments.of(McpPlanInstallResultCatalogPolicyRejectedError.class, """
                        {"kind":"policy-rejected","source":"enterprise-allowlist","message":"Installation is blocked"}
                        """), Arguments.of(McpPlanInstallResultCatalogNetworkFailureError.class, """
                        {"kind":"network-failure","reason":"rate-limited","statusCode":429,
                         "retryAfterSeconds":30,"message":"Retry later"}
                        """), Arguments.of(McpPlanInstallResultCatalogUnsafeRetrievalError.class, """
                        {"kind":"unsafe-retrieval","reason":"blocked-address","message":"Retrieval is blocked"}
                        """), Arguments.of(McpPlanInstallResultCatalogMalformedCardError.class, """
                        {"kind":"malformed-card","reason":"schema-violation",
                         "mediaType":"application/mcp-server-card+json","message":"The card does not match its schema"}
                        """), Arguments.of(McpPlanInstallResultCatalogContractViolationError.class, """
                        {"kind":"contract-violation","reason":"both-url-and-data","message":"Use one card source"}
                        """), Arguments.of(McpPlanInstallResultCatalogUnavailableError.class, """
                        {"kind":"unavailable","reason":"planning-unavailable","message":"Planning is unavailable"}
                        """));
    }

    private static String catalogNegotiationRefusedJson() {
        return """
                {"kind":"negotiation-refused","reason":"unsupported-capability","runtimeProtocolVersion":3,
                 "minimumSupportedProtocolVersion":3,
                 "supportedCapabilities":["mcp-server-card","legacy-mcp-server-card","ai-skill-discovery",
                                          "agent-plugin-discovery","mcp-install-planning"],
                 "unsupportedCapabilities":["catalog-selection"],"message":"The required capability is unavailable"}
                """;
    }

    private static <T> void assertCatalogErrorRoundTrip(Class<T> root, Class<? extends T> subtype, String json,
            Function<T, String> kind) throws Exception {
        var mapper = new ObjectMapper();
        var expected = mapper.readTree(json);
        assertTrue(root.isAssignableFrom(subtype));

        var result = mapper.readValue(json, root);
        assertInstanceOf(root, result);
        assertEquals(subtype, result.getClass());
        assertEquals(expected.get("kind").asText(), kind.apply(result));

        var serialized = mapper.writeValueAsString(result);
        assertEquals(expected, mapper.readTree(serialized));
        var roundTripped = mapper.readValue(serialized, root);
        assertInstanceOf(root, roundTripped);
        assertEquals(subtype, roundTripped.getClass());
        assertEquals(expected.get("kind").asText(), kind.apply(roundTripped));
        assertEquals(expected, mapper.readTree(mapper.writeValueAsString(roundTripped)));
    }

    @Test
    void sessionAgentGetCurrentResult_null_agent() throws Exception {
        var mapper = new ObjectMapper();
        var result = SessionAgentGetCurrentResult.builder().agent(null).build();
        assertNull(result.agent());
        assertEquals(mapper.readTree("{\"agent\":null}"), mapper.valueToTree(result));
        assertEquals(result, mapper.readValue("{\"agent\":null}", SessionAgentGetCurrentResult.class));
        var missing = assertThrows(IllegalStateException.class, () -> SessionAgentGetCurrentResult.builder().build());
        assertEquals("Missing required property: agent", missing.getMessage());
    }

    @Test
    void sessionAgentSelectResult_nested() throws Exception {
        Map<String, List<Object>> hooks = Map.of("Stop",
                List.of(Map.of("type", "command", "command", "echo scoped-stop")));
        var agent = AgentInfo.builder().name("selected").displayName("Selected").description("The selected agent")
                .pluginId("mp:catalog::tools").path("/path/to/selected").id("agent-selected").source(null)
                .userInvocable(null).disableModelInvocation(null).tools(null).model(null).models(null).modelPolicy(null)
                .reasoningEffort(null).mcpServers(null).skills(null).prompt(null).hooks(hooks).build();
        var result = SessionAgentSelectResult.builder().agent(agent).build();
        assertEquals("selected", result.agent().name());
        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(result);
        assertEquals("mp:catalog::tools", mapper.readTree(json).get("agent").get("pluginId").asText());
        assertEquals("echo scoped-stop",
                mapper.readTree(json).get("agent").get("hooks").get("Stop").get(0).get("command").asText());
        assertEquals(hooks, mapper.readValue(json, SessionAgentSelectResult.class).agent().hooks());
    }

    // ── Complex nested result records ──────────────────────────────────────

    @Test
    void modelsListResult_deserializesAutoTierMetadata() throws Exception {
        var result = new ObjectMapper().readValue("""
                {
                  "models": [],
                  "auto": {
                    "defaultTier": "balance",
                    "tiers": [
                      {"id": "balance", "displayName": "Balance", "description": "Balanced", "type": "auto",
                       "status": {"enabled": true}},
                      {"id": "hydrafusion", "displayName": "High", "description": "Fusion", "type": "fusion",
                       "status": {"enabled": false, "reason": "policy", "message": "Contact admin"}}
                    ]
                  }
                }
                """, ModelsListResult.class);

        assertEquals("balance", result.auto().defaultTier());
        assertNull(result.auto().providerId());
        assertEquals(List.of("balance", "hydrafusion"),
                result.auto().tiers().stream().map(AutoTierDescriptor::id).toList());
        var fusion = result.auto().tiers().get(1);
        assertEquals("High", fusion.displayName());
        assertEquals("fusion", fusion.type());
        assertEquals(AutoTierStatus.builder().enabled(false).pickerVisible(null).reason("policy")
                .message("Contact admin").build(), fusion.status());
    }
}
