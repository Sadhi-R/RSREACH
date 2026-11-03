package com.rsreach.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import com.rsreach.persistence.webhook.ProviderWebhookRepository;
import com.rsreach.persistence.webhook.WebhookIngestCommand;
import com.rsreach.persistence.webhook.WebhookIngestResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingests and applies Razorpay webhook events.
 *
 * <h3>Security</h3>
 * Signature verification is done by the caller (typically a controller) before invoking
 * {@link #ingest}. The {@code signatureValid} flag is stored alongside the payload so
 * {@link #applyEvent} can reject events with invalid signatures without re-verifying.
 * Payloads are never logged.
 *
 * <h3>Idempotency</h3>
 * Deduplication is by {@code (provider, connected_account_ref, provider_event_id)}.
 * A duplicate event returns the existing webhook event id without reprocessing.
 *
 * <h3>Tenant resolution</h3>
 * Razorpay webhooks at the platform level may not contain tenant context in all cases.
 * The service attempts to parse {@code payload.payment.entity.notes.tenant_id} for standard
 * payment events, and a top-level {@code tenantId} field for mock/test payloads. The
 * {@code provider_webhook_events.tenant_id} column is nullable; if the tenant cannot be resolved
 * at ingest time, it remains null and {@link #applyEvent} will ignore the event.
 */
@Service
public class BillingWebhookService {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookService.class);
    private static final String PROVIDER = "razorpay";
    private static final String ACCOUNT_REF = "platform";

    private final PaymentGateway paymentGateway;
    private final ProviderWebhookRepository webhookRepository;
    private final OutboxRepository outboxRepository;
    private final PaymentService paymentService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BillingWebhookService(
            PaymentGateway paymentGateway,
            ProviderWebhookRepository webhookRepository,
            OutboxRepository outboxRepository,
            PaymentService paymentService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.paymentGateway = paymentGateway;
        this.webhookRepository = webhookRepository;
        this.outboxRepository = outboxRepository;
        this.paymentService = paymentService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Signature verification
    // -----------------------------------------------------------------------

    /**
     * Delegates to the configured {@link PaymentGateway} to verify the Razorpay signature.
     *
     * @param signatureHeader value of the {@code X-Razorpay-Signature} header
     * @param rawBody         unmodified request body bytes
     * @return {@code true} if the signature is valid
     */
    public boolean verifySignature(String signatureHeader, byte[] rawBody) {
        return paymentGateway.verifyWebhookSignature(signatureHeader, rawBody);
    }

    // -----------------------------------------------------------------------
    // Ingest
    // -----------------------------------------------------------------------

    /**
     * Durably stores the raw webhook payload and enqueues it for async processing via the outbox.
     *
     * <p>Duplicate events (same provider event id) are detected by the unique index in
     * {@code provider_webhook_events} and returned idempotently.
     *
     * @param rawBody       raw bytes of the HTTP request body
     * @param signatureValid whether the caller verified the signature before calling this method
     * @return the webhook event id (existing id on duplicate)
     */
    @Transactional
    public UUID ingest(byte[] rawBody, boolean signatureValid) {
        if (rawBody == null || rawBody.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Webhook body must not be empty");
        }

        String payloadJson = new String(rawBody, StandardCharsets.UTF_8);
        String providerEventId;
        String eventType;
        UUID tenantId = null;

        try {
            JsonNode root = objectMapper.readTree(rawBody);

            providerEventId = root.path("id").asText(null);
            eventType = root.path("event").asText("unknown");

            // Standard Razorpay: notes are inside payload.payment.entity.notes
            JsonNode notes = root.at("/payload/payment/entity/notes");
            if (!notes.isMissingNode() && notes.isObject()) {
                tenantId = parseUuid(notes.path("tenant_id").asText(null));
            }

            // Fallback: top-level tenantId field for mock/test payloads
            if (tenantId == null && root.has("tenantId")) {
                tenantId = parseUuid(root.get("tenantId").asText(null));
            }
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Webhook payload is not valid JSON");
        }

        if (providerEventId == null || providerEventId.isBlank()) {
            // Graceful fallback so we don't lose unknown-format events
            providerEventId = "gen_" + UUID.randomUUID();
            log.warn("Razorpay webhook missing 'id' field; using generated id={}", providerEventId);
        }

        String payloadHash = sha256Hex(rawBody);

        WebhookIngestResult result = webhookRepository.ingest(new WebhookIngestCommand(
                PROVIDER,
                ACCOUNT_REF,
                providerEventId,
                eventType,
                null,       // eventTime — parseable from payload if needed
                tenantId,
                null,       // workspaceId — billing webhooks are tenant-level
                signatureValid,
                payloadJson,
                payloadHash
        ));

        if (result.inserted()) {
            outboxRepository.append(OutboxAppendCommand.of(
                    "billing",
                    result.eventId(),
                    BillingModels.EVENT_BILLING_WEBHOOK_APPLY,
                    "{\"webhookEventId\":\"" + result.eventId() + "\"}",
                    tenantId,
                    null,
                    null,
                    BillingModels.EVENT_BILLING_WEBHOOK_APPLY + ":" + result.eventId()
            ));
            log.info("Razorpay webhook ingested eventId={} type={} tenant={} sigValid={}",
                    result.eventId(), eventType, tenantId, signatureValid);
        } else {
            log.debug("Razorpay webhook duplicate providerEventId={} returning existing={}",
                    providerEventId, result.eventId());
        }

        return result.eventId();
    }

    // -----------------------------------------------------------------------
    // Apply (called by worker via outbox)
    // -----------------------------------------------------------------------

    /**
     * Processes a previously ingested webhook event.
     * Events with an invalid signature are marked IGNORED and not applied.
     * Only {@code payment.captured} is handled in MVP.
     *
     * @param webhookEventId the UUID of the {@code provider_webhook_events} row
     */
    @Transactional
    public void applyEvent(UUID webhookEventId) {
        List<EventRow> rows = jdbcTemplate.query(
                """
                SELECT payload_json::text AS payload_json, event_type,
                       tenant_id::text     AS tenant_id,
                       signature_valid
                FROM provider_webhook_events
                WHERE id = ?
                """,
                (rs, i) -> new EventRow(
                        rs.getString("payload_json"),
                        rs.getString("event_type"),
                        rs.getString("tenant_id"),
                        rs.getBoolean("signature_valid")
                ),
                webhookEventId
        );

        if (rows.isEmpty()) {
            log.warn("applyEvent: webhook event not found id={}", webhookEventId);
            return;
        }

        EventRow row = rows.get(0);

        if (!row.signatureValid()) {
            log.warn("applyEvent: ignoring webhook with invalid signature id={}", webhookEventId);
            jdbcTemplate.update(
                    """
                    UPDATE provider_webhook_events
                    SET processing_status = 'IGNORED',
                        last_error = 'signature_invalid',
                        updated_at = NOW()
                    WHERE id = ?
                    """,
                    webhookEventId
            );
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(row.payloadJson());
            String eventType = row.eventType();

            if ("payment.captured".equals(eventType)) {
                handlePaymentCaptured(webhookEventId, root, row.tenantIdStr());
            } else {
                log.debug("applyEvent: unhandled event type={} id={}", eventType, webhookEventId);
            }

            webhookRepository.markProcessed(webhookEventId);
        } catch (ApiException ex) {
            // Let the worker handle retry/dead-letter
            throw new RuntimeException("Billing webhook application failed id=" + webhookEventId
                    + ": " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new RuntimeException("Billing webhook application failed id=" + webhookEventId, ex);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void handlePaymentCaptured(UUID webhookEventId, JsonNode root, String tenantIdStr) {
        JsonNode paymentEntity = root.at("/payload/payment/entity");
        if (paymentEntity.isMissingNode()) {
            log.warn("payment.captured missing /payload/payment/entity webhookId={}", webhookEventId);
            return;
        }

        String providerPaymentId = paymentEntity.path("id").asText(null);
        String providerOrderId = paymentEntity.path("order_id").asText(null);
        long amountPaise = paymentEntity.path("amount").asLong(0L);

        // Resolve tenant: stored at ingest time, or from notes in entity
        UUID tenantId = parseUuid(tenantIdStr);
        if (tenantId == null) {
            JsonNode notes = paymentEntity.path("notes");
            tenantId = parseUuid(notes.path("tenant_id").asText(null));
        }

        if (tenantId == null) {
            log.error("payment.captured: cannot resolve tenant_id webhookId={}; ignoring",
                    webhookEventId);
            jdbcTemplate.update(
                    """
                    UPDATE provider_webhook_events
                    SET processing_status = 'IGNORED',
                        last_error = 'tenant_id_not_found',
                        updated_at = NOW()
                    WHERE id = ?
                    """,
                    webhookEventId
            );
            return;
        }

        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            log.warn("payment.captured: missing provider payment id webhookId={}", webhookEventId);
            return;
        }

        paymentService.applyRazorpayPayment(tenantId, providerPaymentId, providerOrderId, amountPaise);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record EventRow(
            String payloadJson,
            String eventType,
            String tenantIdStr,
            boolean signatureValid) {
    }
}
