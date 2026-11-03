package com.rsreach.communications;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CommunicationModels {

    public static final String EVENT_MESSAGE_SEND = "MESSAGE_SEND";
    public static final String EVENT_WEBHOOK_APPLY = "WEBHOOK_APPLY";
    public static final String EVENT_MAILBOX_SYNC = "MAILBOX_SYNC";
    public static final String EVENT_CAMPAIGN_ENQUEUE = "CAMPAIGN_ENQUEUE";
    public static final String EVENT_SCHEDULED_MESSAGE_FIRE = "SCHEDULED_MESSAGE_FIRE";

    public static final String CHANNEL_WHATSAPP = "WHATSAPP";
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_INTERNAL_TEST = "INTERNAL_TEST";

    private CommunicationModels() {
    }

    public record ConnectedAccountView(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String provider,
            String ownershipMode,
            String displayName,
            String externalAccountRef,
            String status,
            Instant createdAt,
            Instant disconnectedAt
    ) {
    }

    public record CreateConnectedAccountCommand(
            String provider,
            String ownershipMode,
            String displayName,
            String externalAccountRef,
            String accessToken,
            Map<String, Object> metadata
    ) {
    }

    public record ConversationView(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String channel,
            UUID contactId,
            UUID connectedAccountId,
            String subject,
            String externalThreadKey,
            String status,
            Instant lastMessageAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CreateConversationCommand(
            String channel,
            UUID contactId,
            UUID connectedAccountId,
            String subject,
            String externalThreadKey
    ) {
    }

    public record MessageView(
            UUID id,
            UUID conversationId,
            String direction,
            String channel,
            String bodyText,
            String bodyPreview,
            String purpose,
            String destinationAddress,
            String providerMessageId,
            String status,
            Instant acceptedAt,
            Instant sentAt,
            Instant deliveredAt,
            Instant readAt,
            Instant failedAt,
            Instant createdAt
    ) {
    }

    public record AttachmentMeta(
            String filename,
            String mimeType,
            Long sizeBytes,
            String providerMediaId
    ) {
    }

    public record ComposeMessageCommand(
            String bodyText,
            String purpose,
            String destinationAddress,
            String idempotencyKey,
            String templateName,
            List<AttachmentMeta> attachments
    ) {
    }

    public record ConversationPage(List<ConversationView> items, int page, int size, boolean hasNext) {
    }

    public record MessagePage(List<MessageView> items, int page, int size, boolean hasNext) {
    }

    public record AssignmentView(
            UUID id,
            UUID conversationId,
            UUID assigneeUserId,
            UUID assignedBy,
            String status,
            Instant assignedAt
    ) {
    }

    public record NoteView(
            UUID id,
            UUID conversationId,
            UUID authorUserId,
            String bodyText,
            Instant createdAt
    ) {
    }

    public record TemplateView(
            UUID id,
            String channel,
            String name,
            String language,
            String category,
            String status,
            String bodyPreview
    ) {
    }
}
