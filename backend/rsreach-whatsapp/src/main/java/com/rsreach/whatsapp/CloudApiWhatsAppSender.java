package com.rsreach.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConnectedAccountService;
import com.rsreach.communications.MessageSender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Live Meta Cloud API sender. Enabled only when {@code rsreach.whatsapp.live-enabled=true}.
 * Requires operator-supplied token on the connected account and phone-number-id in metadata.
 */
@Component
@Order(0)
@ConditionalOnProperty(prefix = "rsreach.whatsapp", name = "live-enabled", havingValue = "true")
public class CloudApiWhatsAppSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(CloudApiWhatsAppSender.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectedAccountService connectedAccountService;
    private final String apiBaseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public CloudApiWhatsAppSender(
            ConnectedAccountService connectedAccountService,
            @Value("${rsreach.whatsapp.api-base-url:https://graph.facebook.com/v21.0}") String apiBaseUrl
    ) {
        this.connectedAccountService = connectedAccountService;
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
    }

    @Override
    public String channel() {
        return CommunicationModels.CHANNEL_WHATSAPP;
    }

    @Override
    public SendResult send(
            UUID tenantId,
            UUID workspaceId,
            CommunicationModels.ConversationView conversation,
            CommunicationModels.MessageView message
    ) {
        if (conversation.connectedAccountId() == null) {
            return SendResult.fail("NO_ACCOUNT", "WhatsApp connected account is required for live send");
        }
        try {
            String token = connectedAccountService.decryptAccessToken(
                    tenantId, workspaceId, conversation.connectedAccountId());
            String phoneNumberId = connectedAccountService.resolveExternalRef(
                    tenantId, workspaceId, conversation.connectedAccountId());
            if (phoneNumberId == null || phoneNumberId.isBlank()) {
                return SendResult.fail("NO_PHONE_NUMBER_ID", "connected account missing externalAccountRef (phone-number-id)");
            }
            String to = message.destinationAddress();
            String payload = MAPPER.writeValueAsString(Map.of(
                    "messaging_product", "whatsapp",
                    "to", to == null ? "" : to,
                    "type", "text",
                    "text", Map.of("body", message.bodyText() == null ? "" : message.bodyText())
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/" + phoneNumberId + "/messages"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode node = MAPPER.readTree(response.body());
                String providerId = node.path("messages").path(0).path("id").asText(null);
                return SendResult.ok(providerId == null ? "wa-live-" + message.id() : providerId);
            }
            String code = String.valueOf(response.statusCode());
            if (ProviderErrorClassifier.isRetryable(code, response.body())) {
                throw new IllegalStateException("retryable_whatsapp_send status=" + code);
            }
            log.warn("whatsapp_live_send_failed status={} messageId={}", response.statusCode(), message.id());
            return SendResult.fail(code, "Cloud API rejected send");
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("whatsapp_live_send_error: " + ex.getMessage(), ex);
        }
    }
}
