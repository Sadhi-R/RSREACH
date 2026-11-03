package com.rsreach.api.web.billing;

import com.rsreach.billing.BillingWebhookService;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives raw Razorpay webhook POSTs.
 *
 * <h3>Security</h3>
 * Signature is verified first. Invalid signatures are rejected with 401 and the payload is
 * NOT ingested. This ensures only Razorpay-signed events reach the processing pipeline.
 *
 * <h3>Idempotency</h3>
 * The underlying {@link BillingWebhookService#ingest} deduplicates on provider event ID;
 * duplicate POSTs return the same webhook event ID.
 *
 * <h3>Response</h3>
 * Always returns 200 after a successful ingest attempt so Razorpay stops retrying.
 * Only signature failures (401) prevent ingest.
 */
@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final BillingWebhookService billingWebhookService;

    public RazorpayWebhookController(BillingWebhookService billingWebhookService) {
        this.billingWebhookService = billingWebhookService;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signatureHeader,
            @RequestBody byte[] rawBody
    ) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("razorpay_webhook_missing_signature");
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Missing X-Razorpay-Signature header");
        }

        boolean signatureValid = billingWebhookService.verifySignature(signatureHeader, rawBody);
        if (!signatureValid) {
            log.warn("razorpay_webhook_invalid_signature");
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Razorpay signature verification failed");
        }

        UUID webhookEventId = billingWebhookService.ingest(rawBody, true);
        log.info("razorpay_webhook_ingested eventId={}", webhookEventId);
        return ResponseEntity.ok(Map.of("received", true, "eventId", webhookEventId.toString()));
    }
}
