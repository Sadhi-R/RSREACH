package com.rsreach.billing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Razorpay adapter implementing {@link PaymentGateway}.
 *
 * <p>Mock signature mode (blank webhook secret + {@code mock-valid} header) is allowed only on the
 * {@code local} Spring profile. Non-local profiles fail closed when the webhook secret is blank.
 *
 * <p>Live Orders HTTP runs only when {@code live-orders=true} (or legacy {@code live-enabled=true})
 * and key credentials are present.
 */
@Component
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);
    private static final String ORDERS_URL = "https://api.razorpay.com/v1/orders";

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final boolean liveOrders;
    private final boolean localProfile;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public RazorpayPaymentGateway(
            @Value("${rsreach.billing.razorpay.key-id:}") String keyId,
            @Value("${rsreach.billing.razorpay.key-secret:}") String keySecret,
            @Value("${rsreach.billing.razorpay.webhook-secret:}") String webhookSecret,
            @Value("${rsreach.billing.razorpay.live-orders:${rsreach.billing.razorpay.live-enabled:false}}")
                    boolean liveOrders,
            Environment environment
    ) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        this.liveOrders = liveOrders;
        this.localProfile = environment.matchesProfiles("local");
    }

    @Override
    public String provider() {
        return "razorpay";
    }

    @Override
    public PaymentGateway.CheckoutOrder createOrder(
            UUID tenantId, UUID subscriptionId, long amountPaise, String currency, String receipt
    ) {
        if (keySecret == null || keySecret.isBlank()) {
            if (!localProfile) {
                throw new IllegalStateException(
                        "Razorpay key-secret is required outside the local profile");
            }
            String orderId = "order_mock_" + UUID.randomUUID().toString().replace("-", "");
            log.info("Razorpay mock mode: created synthetic order {} tenant={}", orderId, tenantId);
            return new PaymentGateway.CheckoutOrder(orderId, currency, amountPaise, "rzp_test_mock");
        }
        if (liveOrders) {
            return createLiveOrder(tenantId, amountPaise, currency, receipt);
        }
        String orderId = "order_local_" + UUID.randomUUID().toString().replace("-", "");
        log.info("Razorpay order created locally (live-orders=false): {} tenant={}", orderId, tenantId);
        return new PaymentGateway.CheckoutOrder(orderId, currency, amountPaise, keyId);
    }

    @Override
    public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            if (!localProfile) {
                log.warn("Razorpay webhook secret blank outside local profile; rejecting signature");
                return false;
            }
            return "mock-valid".equals(signatureHeader);
        }
        if (signatureHeader == null || rawBody == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody);
            String computed = HexFormat.of().formatHex(digest);
            return constantTimeEquals(computed, signatureHeader.trim());
        } catch (Exception ex) {
            log.warn("Razorpay webhook HMAC verification failed: {}", ex.getMessage());
            return false;
        }
    }

    private PaymentGateway.CheckoutOrder createLiveOrder(
            UUID tenantId, long amountPaise, String currency, String receipt
    ) {
        try {
            String body = "{\"amount\":" + amountPaise
                    + ",\"currency\":\"" + escapeJson(currency)
                    + "\",\"receipt\":\"" + escapeJson(receipt == null ? tenantId.toString() : receipt)
                    + "\",\"notes\":{\"tenant_id\":\"" + tenantId + "\"}}";
            String basic = Base64.getEncoder().encodeToString(
                    (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ORDERS_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("razorpay_order_http_" + response.statusCode());
            }
            String orderId = extractJsonString(response.body(), "id");
            if (orderId == null || orderId.isBlank()) {
                throw new IllegalStateException("razorpay_order_missing_id");
            }
            log.info("Razorpay live order created {} tenant={}", orderId, tenantId);
            return new PaymentGateway.CheckoutOrder(orderId, currency, amountPaise, keyId);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("razorpay_order_failed: " + ex.getMessage(), ex);
        }
    }

    private static String extractJsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + needle.length());
        int startQuote = json.indexOf('"', colon + 1);
        int endQuote = json.indexOf('"', startQuote + 1);
        if (startQuote < 0 || endQuote < 0) {
            return null;
        }
        return json.substring(startQuote + 1, endQuote);
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
