package com.rsreach.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConversationService;
import com.rsreach.communications.MessageRepository;
import com.rsreach.communications.WebhookAccountResolutionException;
import com.rsreach.communications.WebhookAccountResolver;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import com.rsreach.persistence.webhook.ProviderWebhookRepository;
import com.rsreach.persistence.webhook.WebhookIngestCommand;
import com.rsreach.persistence.webhook.WebhookIngestResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WhatsAppWebhookService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderWebhookRepository webhookRepository;
    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final OutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final WebhookAccountResolver accountResolver;
    private final String appSecret;
    private final String verifyToken;
    private final boolean allowMockSignatures;

    public WhatsAppWebhookService(
            ProviderWebhookRepository webhookRepository,
            ConversationService conversationService,
            MessageRepository messageRepository,
            OutboxRepository outboxRepository,
            JdbcTemplate jdbcTemplate,
            WebhookAccountResolver accountResolver,
            @Value("${rsreach.whatsapp.app-secret:}") String appSecret,
            @Value("${rsreach.whatsapp.verify-token:}") String verifyToken,
            @Value("${rsreach.webhooks.allow-mock-signatures:true}") boolean allowMockSignatures
    ) {
        this.webhookRepository = webhookRepository;
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.accountResolver = accountResolver;
        this.appSecret = appSecret;
        this.verifyToken = verifyToken;
        this.allowMockSignatures = allowMockSignatures;
    }

    public boolean verifyChallenge(String mode, String token, String challenge) {
        return "subscribe".equals(mode)
                && verifyToken != null
                && !verifyToken.isBlank()
                && verifyToken.equals(token)
                && challenge != null;
    }

    public boolean verifySignature(String header, String body) {
        if (appSecret != null && !appSecret.isBlank()) {
            return MetaWebhookSignature.verifySha256(appSecret, header, body);
        }
        if (!allowMockSignatures) {
            return false;
        }
        return verifyMockSignature(header, body);
    }

    public boolean verifyMockSignature(String header, String body) {
        if (header == null || header.isBlank()) {
            return false;
        }
        return header.equals("mock-valid") || header.equals(hash(body));
    }

    /**
     * Ingests a signature-checked body. Tenant and workspace come from the active connected account
     * whose external ref is the phone-number id in the body.
     *
     * @param provider {@code whatsapp} or the {@code mock} URL alias. The event row is stored as whatsapp.
     */
    @Transactional
    public WebhookIngestResult ingestAuthenticated(String provider, String rawBody) {
        String phoneNumberId = WhatsAppAccountRefExtractor.requireSinglePhoneNumberId(rawBody);
        CommunicationModels.ConnectedAccountView account =
                accountResolver.requireActive(provider, phoneNumberId);
        return ingest(account.tenantId(), account.workspaceId(), phoneNumberId, rawBody, true);
    }

    private WebhookIngestResult ingest(
            UUID tenantId,
            UUID workspaceId,
            String connectedAccountRef,
            String rawBody,
            boolean signatureValid
    ) {
        if (connectedAccountRef == null || connectedAccountRef.isBlank()) {
            throw new WebhookAccountResolutionException(WebhookAccountResolutionException.Reason.MISSING_IDENTIFIER);
        }
        String eventId = hash(rawBody);
        WebhookIngestResult result = webhookRepository.ingest(new WebhookIngestCommand(
                "whatsapp",
                connectedAccountRef,
                eventId,
                "whatsapp.inbound",
                Instant.now(),
                tenantId,
                workspaceId,
                signatureValid,
                rawBody,
                eventId
        ));
        if (result.inserted() && signatureValid) {
            outboxRepository.append(OutboxAppendCommand.of(
                    "provider_webhook_event",
                    result.eventId(),
                    CommunicationModels.EVENT_WEBHOOK_APPLY,
                    "{\"provider\":\"whatsapp\",\"eventId\":\"" + result.eventId() + "\"}",
                    tenantId,
                    workspaceId,
                    null,
                    "WEBHOOK_APPLY:" + result.eventId()
            ));
            // Best-effort immediate apply; durable outbox retries if conversation not ready yet.
            try {
                applyPayload(tenantId, workspaceId, connectedAccountRef, rawBody);
            } catch (Exception ex) {
                log.info("whatsapp_webhook_apply_deferred reason={}", ex.getMessage());
            }
        }
        return result;
    }

    @Transactional
    public void applyPayload(UUID tenantId, UUID workspaceId, String accountRef, String rawBody) {
        try {
            JsonNode root = MAPPER.readTree(rawBody);
            // Simplified Meta-shaped payload support + flat mock {text,from,providerMessageId}
            if (root.has("text")) {
                String from = text(root, "from");
                String providerMessageId = text(root, "providerMessageId", "id");
                String body = root.get("text").asText();
                UUID conversationId = resolveConversation(tenantId, workspaceId, from, accountRef);
                if (conversationId == null) {
                    log.info("whatsapp_inbound_no_conversation from={}", from);
                    return;
                }
                conversationService.appendInbound(
                        tenantId, workspaceId, conversationId, body, providerMessageId, from);
                return;
            }
            JsonNode statuses = root.path("entry").path(0).path("changes").path(0).path("value").path("statuses");
            if (statuses.isArray()) {
                for (JsonNode statusNode : statuses) {
                    applyStatusNode(tenantId, workspaceId, statusNode);
                }
            }
            JsonNode messages = root.path("entry").path(0).path("changes").path(0).path("value").path("messages");
            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    String from = text(msg, "from");
                    String providerMessageId = text(msg, "id");
                    String body = msg.path("text").path("body").asText(extractText(rawBody));
                    UUID conversationId = resolveConversation(tenantId, workspaceId, from, accountRef);
                    if (conversationId == null) {
                        continue;
                    }
                    conversationService.appendInbound(
                            tenantId, workspaceId, conversationId, body, providerMessageId, from);
                }
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("WEBHOOK_APPLY failed: " + ex.getMessage(), ex);
        }
    }

    @Transactional
    public void applyInboundMessage(
            UUID tenantId,
            UUID workspaceId,
            UUID conversationId,
            String body,
            String providerMessageId,
            String fromAddress
    ) {
        conversationService.appendInbound(
                tenantId, workspaceId, conversationId, body, providerMessageId, fromAddress);
    }

    public String extractText(String rawBody) {
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            if (node.has("text")) {
                return node.get("text").asText();
            }
            return rawBody;
        } catch (Exception ex) {
            return rawBody;
        }
    }

    private void applyStatusNode(UUID tenantId, UUID workspaceId, JsonNode statusNode) {
        String providerMessageId = text(statusNode, "id");
        String status = text(statusNode, "status");
        if (providerMessageId == null || status == null) {
            return;
        }
        Optional<CommunicationModels.MessageView> message =
                messageRepository.findByProviderMessageIdAnyChannel(tenantId, workspaceId, providerMessageId);
        if (message.isEmpty()) {
            return;
        }
        String mapped = mapStatus(status);
        if (mapped == null) {
            return;
        }
        conversationService.applyProviderStatus(
                tenantId, workspaceId, message.get().id(), mapped, providerMessageId, status, null, null);
    }

    private static String mapStatus(String status) {
        return switch (status.toLowerCase()) {
            case "sent" -> "SENT";
            case "delivered" -> "DELIVERED";
            case "read" -> "READ";
            case "failed" -> "FAILED";
            default -> null;
        };
    }

    private UUID resolveConversation(UUID tenantId, UUID workspaceId, String from, String accountRef) {
        var byThread = jdbcTemplate.query(
                """
                SELECT id FROM conversations
                WHERE tenant_id = ? AND workspace_id = ? AND channel = 'WHATSAPP'
                  AND external_thread_key = ?
                LIMIT 1
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId, from == null ? accountRef : from
        );
        if (!byThread.isEmpty()) {
            return byThread.get(0);
        }
        var any = jdbcTemplate.query(
                """
                SELECT id FROM conversations
                WHERE tenant_id = ? AND workspace_id = ? AND channel = 'WHATSAPP'
                ORDER BY last_message_at DESC NULLS LAST
                LIMIT 1
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId
        );
        if (!any.isEmpty()) {
            return any.get(0);
        }
        return null;
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key) && !node.get(key).isNull()) {
                return node.get(key).asText();
            }
        }
        return null;
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return UUID.randomUUID().toString();
        }
    }
}
