/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.microsoft.copilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.microsoft.copilot.rpc.Attachment;
import com.microsoft.copilot.rpc.BlobAttachment;
import com.microsoft.copilot.rpc.ExtensionContextAttachment;
import com.microsoft.copilot.rpc.MessageAttachment;
import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.SessionReferenceAttachment;

/**
 * Tests for the {@link MessageAttachment} sealed interface and type-safe
 * attachment handling.
 */
class MessageAttachmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // =========================================================================
    // MessageOptions type safety
    // =========================================================================

    @Test
    void setAttachmentsHandlesNull() {
        MessageOptions options = new MessageOptions();
        options.setAttachments(null);
        assertNull(options.getAttachments());
    }

    @Test
    void getAttachmentsReturnsUnmodifiableList() {
        MessageOptions options = new MessageOptions();
        options.setAttachments(List.of(new Attachment("file", "/a.java", "A")));
        assertThrows(UnsupportedOperationException.class,
                () -> options.getAttachments().add(new Attachment("file", "/b.java", "B")));
    }

    // =========================================================================
    // Jackson serialization
    // =========================================================================

    @Test
    void sessionReferenceRoundTripsThroughMessageOptions() throws Exception {
        String json = """
                {"prompt":"Ask this peer","attachments":[{"type":"session_reference",
                "sessionId":"peer-session","displayName":"Peer","mentionIndex":2}]}
                """;
        MessageOptions options = MAPPER.readValue(json, MessageOptions.class);
        MessageAttachment reference = options.getAttachments().get(0);
        assertInstanceOf(SessionReferenceAttachment.class, reference);
        assertEquals("session_reference", reference.getType());
        assertEquals(MAPPER.readTree(json), MAPPER.readTree(MAPPER.writeValueAsString(options)));
    }

    @Test
    void sessionReferenceCanBeConstructedWithOptionalMentionFields() throws Exception {
        SessionReferenceAttachment reference = new SessionReferenceAttachment().setSessionId("peer-session");
        MessageOptions options = new MessageOptions().setPrompt("Ask this peer").setAttachments(List.of(reference));
        assertEquals(MAPPER.readTree("""
                {"prompt":"Ask this peer","attachments":[{"type":"session_reference","sessionId":"peer-session"}]}
                """), MAPPER.readTree(MAPPER.writeValueAsString(options)));

        reference.setDisplayName("Peer").setMentionIndex(4_294_967_296L);
        assertEquals("peer-session", reference.getSessionId());
        assertEquals("Peer", reference.getDisplayName());
        assertEquals(4_294_967_296L, reference.getMentionIndex());
        SessionReferenceAttachment restored = (SessionReferenceAttachment) MAPPER
                .readValue(MAPPER.writeValueAsString(options), MessageOptions.class).getAttachments().get(0);
        assertEquals(reference.getSessionId(), restored.getSessionId());
        assertEquals(reference.getDisplayName(), restored.getDisplayName());
        assertEquals(reference.getMentionIndex(), restored.getMentionIndex());
    }

    @Test
    void serializeMessageOptionsWithMixedAttachments() throws Exception {
        MessageOptions options = new MessageOptions().setPrompt("Describe")
                .setAttachments(List.of(new Attachment("file", "/a.java", "A"),
                        new BlobAttachment().setData("ZGF0YQ==").setMimeType("image/png").setDisplayName("img.png"),
                        extensionContextAttachment()));

        String json = MAPPER.writeValueAsString(options);
        assertTrue(json.contains("\"type\":\"file\""));
        assertTrue(json.contains("\"path\":\"/a.java\""));
        assertTrue(json.contains("\"type\":\"blob\""));
        assertTrue(json.contains("\"data\":\"ZGF0YQ==\""));
        assertTrue(json.contains("\"mimeType\":\"image/png\""));
        assertTrue(json.contains("\"type\":\"extension_context\""));
        assertTrue(json.contains("\"capturedAt\":\"2026-09-18T20:00:00Z\""));
        assertTrue(json.contains("\"extensionId\":\"scenario-extension\""));
        assertTrue(json.contains("\"canvasId\":\"diff\""));
        assertTrue(json.contains("\"instanceId\":\"diff-17\""));
        assertTrue(json.contains("\"selection\":\"active\""));
    }

    @Test
    void cloneMessageOptionsPreservesAttachments() {
        MessageOptions original = new MessageOptions().setPrompt("test")
                .setAttachments(List.of(new Attachment("file", "/a.java", "A")));

        MessageOptions cloned = original.clone();

        assertEquals(1, cloned.getAttachments().size());
        assertInstanceOf(Attachment.class, cloned.getAttachments().get(0));
        // Verify clone is independent
        assertNotSame(original.getAttachments(), cloned.getAttachments());
    }

    private static ExtensionContextAttachment extensionContextAttachment() {
        return new ExtensionContextAttachment().setCapturedAt("2026-09-18T20:00:00Z")
                .setExtensionId("scenario-extension").setTitle("Selected change").setCanvasId("diff")
                .setInstanceId("diff-17").setPayload(Map.of("selection", "active"));
    }
}
