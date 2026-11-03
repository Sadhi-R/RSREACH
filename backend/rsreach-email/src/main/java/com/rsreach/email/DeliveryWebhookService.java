package com.rsreach.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConversationService;
import com.rsreach.communications.MessageRepository;
import com.rsreach.communications.WebhookAccountResolutionException;
import com.rsreach.communications.WebhookAccountResolver;
import com.rsreach.contacts.ConsentModels;
import com.rsreach.contacts.SuppressionService;
import com.rsreach.persistence.webhook.ProviderWebhookRepository;
import com.rsreach.persistence.webhook.WebhookIngestCommand;
import com.rsreach.persistence.webhook.WebhookIngestResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryWebhookService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWebhookService.class);
    private static final Set<String> PROVIDERS = Set.of("sendgrid", "ses", "mailgun", "gmail", "microsoft_graph");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderWebhookRepository webhookRepository;
    private final MessageRepository messageRepository;
    private final ConversationService conversationService;
    private final SuppressionService suppressionService;
    private final WebhookAccountResolver accountResolver;
    private final boolean allowMockSignatures;
    private final String sendgridWebhookKey;

    public DeliveryWebhookService(
            ProviderWebhookRepository webhookRepository,
            MessageRepository messageRepository,
            ConversationService conversationService,
            SuppressionService suppressionService,
            WebhookAccountResolver accountResolver,
            @Value("${rsreach.webhooks.allow-mock-signatures:true}") boolean allowMockSignatures,
            @Value("${rsreach.email.sendgrid-webhook-verification-key:}") String sendgridWebhookKey
    ) {
        this.webhookRepository = webhookRepository;
        this.messageRepository = messageRepository;
        this.conversationService = conversationService;
        this.suppressionService = suppressionService;
        this.accountResolver = accountResolver;
        this.allowMockSignatures = allowMockSignatures;
        this.sendgridWebhookKey = sendgridWebhookKey;
    }

    public boolean supports(String provider) {
        return provider != null && PROVIDERS.contains(provider.trim().toLowerCase(Locale.ROOT));
    }

    public boolean verifySignature(String provider, String header, String rawBody) {
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if ("sendgrid".equals(normalized) && sendgridWebhookKey != null && !sendgridWebhookKey.isBlank()) {
            // Signed Event Webhook verification is operator-configured; accept matching HMAC hex when provided.
            if (header == null || header.isBlank()) {
                return false;
            }
            return header.equals("sha256=" + hmacHex(sendgridWebhookKey, rawBody))
                    || header.equals(hmacHex(sendgridWebhookKey, rawBody));
        }
        if (!allowMockSignatures) {
            return false;
        }
        return verifyMockSignature(header);
    }

    public boolean verifyMockSignature(String header) {
        return header != null && (header.equals("mock-valid") || header.startsWith("sha256="));
    }

    /**
     * Ingests a signature-checked delivery body. Tenant and workspace come from the active
     * connected account for the provider-specific account id in the body.
     */
    @Transactional
    public WebhookIngestResult ingestAuthenticated(String provider, String rawBody) {
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        String accountRef = DeliveryAccountRefExtractor.requireSingleAccountRef(normalized, rawBody);
        CommunicationModels.ConnectedAccountView account = accountResolver.requireActive(normalized, accountRef);
        return ingest(normalized, account.tenantId(), account.workspaceId(), accountRef, rawBody, true);
    }

    private WebhookIngestResult ingest(
            String provider,
            UUID tenantId,
            UUID workspaceId,
            String accountRef,
            String rawBody,
            boolean signatureValid
    ) {
        if (accountRef == null || accountRef.isBlank()) {
            throw new WebhookAccountResolutionException(WebhookAccountResolutionException.Reason.MISSING_IDENTIFIER);
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        String eventId = hash(rawBody);
        WebhookIngestResult result = webhookRepository.ingest(new WebhookIngestCommand(
                normalized,
                accountRef,
                eventId,
                normalized + ".delivery",
                Instant.now(),
                tenantId,
                workspaceId,
                signatureValid,
                rawBody,
                eventId
        ));
        if (result.inserted() && signatureValid) {
            applyStatus(tenantId, workspaceId, rawBody);
        }
        return result;
    }

    @Transactional
    public void applyStatus(UUID tenantId, UUID workspaceId, String rawBody) {
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            // SendGrid may post an array
            if (node.isArray() && node.size() > 0) {
                node = node.get(0);
            }
            String providerMessageId = text(node, "providerMessageId", "sg_message_id", "mail", "messageId");
            if (providerMessageId == null || providerMessageId.isBlank()) {
                return;
            }
            String event = text(node, "event", "notificationType", "eventType");
            String status = mapStatus(event);
            if (status == null) {
                return;
            }
            Optional<com.rsreach.communications.CommunicationModels.MessageView> message =
                    messageRepository.findByProviderMessageIdAnyChannel(tenantId, workspaceId, providerMessageId);
            if (message.isEmpty()) {
                return;
            }
            conversationService.applyProviderStatus(
                    tenantId, workspaceId, message.get().id(), status, providerMessageId, event, null, null);
            if ("FAILED".equals(status) && event != null
                    && (event.toLowerCase(Locale.ROOT).contains("bounce")
                    || event.toLowerCase(Locale.ROOT).contains("complaint"))) {
                suppressAddress(tenantId, workspaceId, message.get(), event);
            }
        } catch (Exception ex) {
            log.warn("delivery_webhook_apply_failed reason={}", ex.getMessage());
        }
    }

    private void suppressAddress(
            UUID tenantId,
            UUID workspaceId,
            com.rsreach.communications.CommunicationModels.MessageView message,
            String event
    ) {
        if (message.destinationAddress() == null || message.destinationAddress().isBlank()) {
            return;
        }
        try {
            // Best-effort suppression write-back; failures must not roll back durable ingest.
            suppressionService.create(
                    tenantId,
                    workspaceId,
                    null,
                    new ConsentModels.SuppressionCreateCommand(
                            "EMAIL",
                            message.destinationAddress(),
                            event.toLowerCase(Locale.ROOT).contains("complaint")
                                    ? "COMPLAINT via delivery webhook"
                                    : "BOUNCE via delivery webhook"
                    )
            );
        } catch (Exception ex) {
            log.warn("delivery_suppression_skipped reason={}", ex.getMessage());
        }
    }

    private static String mapStatus(String event) {
        if (event == null) {
            return null;
        }
        String e = event.trim().toLowerCase(Locale.ROOT);
        if (e.contains("deliver")) {
            return "DELIVERED";
        }
        if (e.contains("open") || e.contains("read")) {
            return "READ";
        }
        if (e.contains("bounce") || e.contains("complaint") || e.contains("drop") || e.contains("fail")) {
            return "FAILED";
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

    private static String hmacHex(String key, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return "";
        }
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
