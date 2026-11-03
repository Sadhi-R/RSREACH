package com.rsreach.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.communications.CommunicationModels;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rsreach.email", name = "delivery-live-enabled", havingValue = "true")
public class SendGridDeliveryConnector implements DeliveryConnector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBaseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public SendGridDeliveryConnector(
            @Value("${rsreach.email.sendgrid-api-base-url:https://api.sendgrid.com/v3}") String apiBaseUrl
    ) {
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
    }

    @Override
    public String provider() {
        return "sendgrid";
    }

    @Override
    public DeliveryResult send(
            UUID tenantId,
            UUID workspaceId,
            CommunicationModels.ConversationView conversation,
            CommunicationModels.MessageView message,
            String accessToken
    ) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "personalizations", java.util.List.of(Map.of(
                            "to", java.util.List.of(Map.of("email", message.destinationAddress()))
                    )),
                    "from", Map.of("email", "noreply@rsreach.local"),
                    "subject", conversation.subject() == null ? "Message" : conversation.subject(),
                    "content", java.util.List.of(Map.of(
                            "type", "text/plain",
                            "value", message.bodyText() == null ? "" : message.bodyText()
                    ))
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/mail/send"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String providerId = response.headers().firstValue("X-Message-Id")
                        .orElse("sg-" + message.id());
                return DeliveryResult.ok(providerId);
            }
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new IllegalStateException("retryable_sendgrid status=" + response.statusCode());
            }
            return DeliveryResult.fail(String.valueOf(response.statusCode()), "SendGrid rejected send");
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("sendgrid_send_error: " + ex.getMessage(), ex);
        }
    }
}
