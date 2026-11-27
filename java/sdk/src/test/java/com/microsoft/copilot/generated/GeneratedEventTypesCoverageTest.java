/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot.generated;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;

/**
 * Wire-shape guards for generated session event payloads that the reflective
 * round-trip suite cannot express: optional nested fusion attribution and the
 * removed parentToolCallId mirrors.
 */
public class GeneratedEventTypesCoverageTest {

    private static final ObjectMapper MAPPER = createMapper();

    private static ObjectMapper createMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    private static SessionEvent parse(String json) throws Exception {
        return MAPPER.readValue(json, SessionEvent.class);
    }

    @Test
    void permissionRequestedPayloadBuilderRoutesBothFusionShapesWithoutPositionalPadding() throws Exception {
        var withoutFusion = PermissionRequestedEvent.PermissionRequestedEventData.builder().requestId("permission-1")
                .permissionRequest(Map.of("kind", "read")).build();
        assertNull(withoutFusion.fusion());
        var withoutFusionEvent = new PermissionRequestedEvent();
        withoutFusionEvent.setData(withoutFusion);
        var parsedWithoutFusion = parse(MAPPER.writeValueAsString(withoutFusionEvent));
        assertInstanceOf(PermissionRequestedEvent.class, parsedWithoutFusion);
        assertNull(((PermissionRequestedEvent) parsedWithoutFusion).getData().fusion());

        var withFusion = PermissionRequestedEvent.PermissionRequestedEventData.builder().requestId("permission-2")
                .permissionRequest(Map.of("kind", "write"))
                .fusion(FusionAttribution.builder().fusionId("fusion-1").syntheticModel("fusion-model")
                        .policy("parallel").pattern("fan-out").phaseId("phase-1").build())
                .build();
        var withFusionEvent = new PermissionRequestedEvent();
        withFusionEvent.setData(withFusion);
        var parsedWithFusion = parse(MAPPER.writeValueAsString(withFusionEvent));
        assertInstanceOf(PermissionRequestedEvent.class, parsedWithFusion);
        var routedData = ((PermissionRequestedEvent) parsedWithFusion).getData();
        assertEquals("fusion-1", routedData.fusion().fusionId());
        assertEquals("phase-1", routedData.fusion().phaseId());
    }

    @Test
    void removedParentToolCallIdMirrorsLeaveHookOwnershipSupported() throws Exception {
        for (var recordType : List.of(AssistantMessageEvent.AssistantMessageEventData.class,
                AssistantMessageDeltaEvent.AssistantMessageDeltaEventData.class,
                AssistantUsageEvent.AssistantUsageEventData.class,
                ToolExecutionStartEvent.ToolExecutionStartEventData.class,
                ToolExecutionCompleteEvent.ToolExecutionCompleteEventData.class)) {
            assertThrows(NoSuchMethodException.class, () -> recordType.getMethod("parentToolCallId"),
                    () -> recordType.getSimpleName() + ".parentToolCallId must be removed");
        }
        assertFalse(
                HookEndEvent.HookEndEventData.class.getMethod("parentToolCallId").isAnnotationPresent(Deprecated.class),
                "HookEndEventData.parentToolCallId must remain supported");
    }
}
