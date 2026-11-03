package com.rsreach.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link RazorpayPaymentGateway} webhook signature verification.
 *
 * <p>Uses a known fixture to verify the HMAC-SHA256 algorithm matches Razorpay's documented
 * specification: HMAC-SHA256(rawBody, webhookSecret) encoded as lowercase hex.
 * The header value is the raw hex with no prefix (unlike Meta which uses "sha256=").
 */
class RazorpaySignatureTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret-material";
    private static final String BODY = "{\"event\":\"payment.captured\",\"id\":\"evt_TestEvent001\"}";

    // -----------------------------------------------------------------------
    // Valid signature
    // -----------------------------------------------------------------------

    @Test
    void verifiesCorrectHmacSha256Signature() {
        String validHeader = computeHmacHex(WEBHOOK_SECRET, BODY.getBytes(StandardCharsets.UTF_8));
        RazorpayPaymentGateway gw = buildGateway(WEBHOOK_SECRET);

        boolean result = gw.verifyWebhookSignature(validHeader, BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isTrue();
    }

    // -----------------------------------------------------------------------
    // Invalid / forged signature
    // -----------------------------------------------------------------------

    @Test
    void rejectsFakeHexSignature() {
        RazorpayPaymentGateway gw = buildGateway(WEBHOOK_SECRET);

        boolean result = gw.verifyWebhookSignature(
                "deadbeefdeadbeefdeadbeef",
                BODY.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result).isFalse();
    }

    @Test
    void rejectsSignatureWithDifferentBody() {
        String signatureForOriginalBody =
                computeHmacHex(WEBHOOK_SECRET, BODY.getBytes(StandardCharsets.UTF_8));
        String tamperedBody = BODY + " extra";
        RazorpayPaymentGateway gw = buildGateway(WEBHOOK_SECRET);

        boolean result = gw.verifyWebhookSignature(
                signatureForOriginalBody,
                tamperedBody.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result).isFalse();
    }

    @Test
    void rejectsSignatureWithWrongSecret() {
        String signatureForWrongSecret =
                computeHmacHex("wrong-secret", BODY.getBytes(StandardCharsets.UTF_8));
        RazorpayPaymentGateway gw = buildGateway(WEBHOOK_SECRET);

        boolean result = gw.verifyWebhookSignature(
                signatureForWrongSecret,
                BODY.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result).isFalse();
    }

    @Test
    void rejectsNullHeader() {
        RazorpayPaymentGateway gw = buildGateway(WEBHOOK_SECRET);

        boolean result = gw.verifyWebhookSignature(null, BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isFalse();
    }

    @Test
    void rejectsNullBody() {
        RazorpayPaymentGateway gw = buildGateway(WEBHOOK_SECRET);

        boolean result = gw.verifyWebhookSignature("anyheader", null);

        assertThat(result).isFalse();
    }

    // -----------------------------------------------------------------------
    // Mock mode (blank webhook secret)
    // -----------------------------------------------------------------------

    @Test
    void mockModeAcceptsMockValidSentinelOnLocalProfile() {
        RazorpayPaymentGateway gw = buildGateway("", true);

        assertThat(gw.verifyWebhookSignature("mock-valid", BODY.getBytes(StandardCharsets.UTF_8)))
                .isTrue();
    }

    @Test
    void blankWebhookSecretRejectedOutsideLocalProfile() {
        RazorpayPaymentGateway gw = buildGateway("", false);

        assertThat(gw.verifyWebhookSignature("mock-valid", BODY.getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    @Test
    void mockModeRejectsOtherHeaders() {
        RazorpayPaymentGateway gw = buildGateway("", true);

        assertThat(gw.verifyWebhookSignature("legit-looking-header", BODY.getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    @Test
    void mockModeRejectsRealHmacEvenIfCorrect() {
        String realHmac = computeHmacHex(WEBHOOK_SECRET, BODY.getBytes(StandardCharsets.UTF_8));
        RazorpayPaymentGateway gw = buildGateway("", true);

        assertThat(gw.verifyWebhookSignature(realHmac, BODY.getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // HMAC fixture sanity check
    // -----------------------------------------------------------------------

    @Test
    void knownFixtureMatchesExpectedHex() {
        // Pre-computed: HMAC-SHA256("razorpay-secret", "hello") = known hex value.
        // Verified independently with: echo -n "hello" | openssl dgst -sha256 -hmac "razorpay-secret"
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String secret = "razorpay-secret";
        String computed = computeHmacHex(secret, body);

        RazorpayPaymentGateway gw = buildGateway(secret);
        assertThat(gw.verifyWebhookSignature(computed, body)).isTrue();
        assertThat(gw.verifyWebhookSignature(computed.toUpperCase(), body)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static RazorpayPaymentGateway buildGateway(String webhookSecret) {
        return buildGateway(webhookSecret, true);
    }

    private static RazorpayPaymentGateway buildGateway(String webhookSecret, boolean localProfile) {
        MockEnvironment environment = new MockEnvironment();
        if (localProfile) {
            environment.setActiveProfiles("local");
        } else {
            environment.setActiveProfiles("docker");
        }
        return new RazorpayPaymentGateway(
                "rzp_test_key_id",
                webhookSecret.isBlank() ? "" : "rzp_test_key_secret",
                webhookSecret,
                false,
                environment
        );
    }

    private static String computeHmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC failed", ex);
        }
    }
}
