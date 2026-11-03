package com.rsreach.billing;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout, manual payment recording, and idempotent Razorpay payment application.
 *
 * <h3>Activation rule</h3>
 * A subscription is only activated by either:
 * <ul>
 *   <li>A verified Razorpay webhook processed by {@link BillingWebhookService#applyEvent}.
 *   <li>An explicit manual payment recorded by an authorised staff member.
 * </ul>
 * The browser checkout success redirect is NOT proof of payment and MUST NOT activate
 * the subscription. Activation always goes through {@link SubscriptionService#applyPaidActivation}.
 *
 * <h3>Idempotency</h3>
 * {@link #applyRazorpayPayment} is idempotent on {@code (provider, provider_payment_id)} using
 * a unique index in the database; calling it twice with the same payment ID is a no-op.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SubscriptionService subscriptionService;
    private final PlanCatalogService planCatalogService;
    private final InvoiceService invoiceService;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;

    public PaymentService(
            JdbcTemplate jdbcTemplate,
            SubscriptionService subscriptionService,
            PlanCatalogService planCatalogService,
            InvoiceService invoiceService,
            PaymentGateway paymentGateway,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.subscriptionService = subscriptionService;
        this.planCatalogService = planCatalogService;
        this.invoiceService = invoiceService;
        this.paymentGateway = paymentGateway;
        this.auditService = auditService;
    }

    // -----------------------------------------------------------------------
    // Checkout
    // -----------------------------------------------------------------------

    /**
     * Initiates checkout for the tenant's current subscription.
     * Creates an ISSUED invoice, a CREATED payment record, and a Razorpay order.
     * The subscription is NOT activated here; activation happens after webhook confirmation.
     *
     * @throws ApiException NOT_FOUND if no subscription exists
     * @throws ApiException CONFLICT if the plan has no price (free plan, no checkout needed)
     */
    @Transactional
    public BillingModels.CheckoutResult checkout(UUID tenantId, UUID actorId) {
        BillingModels.SubscriptionView sub = subscriptionService.getSubscription(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No subscription found for tenant: " + tenantId));

        String status = sub.status();
        if (BillingModels.STATUS_CANCELLED.equals(status)
                || BillingModels.STATUS_EXPIRED.equals(status)
                || BillingModels.STATUS_SUSPENDED.equals(status)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Cannot checkout; subscription is " + status);
        }

        BillingModels.PlanVersionView pv = planCatalogService.getPlanVersion(sub.planVersionId());
        if (pv.pricePaise() == null || pv.pricePaise() == 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Current plan has no price; checkout is not required");
        }

        BillingModels.InvoiceView invoice =
                invoiceService.createIssuedInvoice(tenantId, sub.id(), pv.pricePaise());

        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO billing_payment (
                    id, tenant_id, invoice_id, subscription_id, method, status,
                    amount_paise, currency, provider, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'RAZORPAY', 'CREATED', ?, 'INR', 'razorpay', NOW(), NOW())
                """,
                paymentId, tenantId, invoice.id(), sub.id(), pv.pricePaise()
        );

        String receipt = invoice.invoiceNumber();
        PaymentGateway.CheckoutOrder order = paymentGateway.createOrder(
                tenantId, sub.id(), pv.pricePaise(), "INR", receipt);

        jdbcTemplate.update(
                "UPDATE billing_payment SET provider_order_id = ?, updated_at = NOW() WHERE id = ?",
                order.orderId(), paymentId
        );

        log.info("Checkout created tenant={} invoice={} orderId={}", tenantId, invoice.id(), order.orderId());
        return new BillingModels.CheckoutResult(
                sub.id(), invoice.id(), paymentId,
                order.orderId(), order.keyId(), pv.pricePaise(), "INR");
    }

    // -----------------------------------------------------------------------
    // Manual / bank transfer payment
    // -----------------------------------------------------------------------

    /**
     * Records a manual or bank-transfer payment by an authorised staff member.
     * Creates an invoice, captures the payment, marks the invoice PAID, and activates
     * the subscription. The staff actor is audited.
     *
     * @param staffId the authenticated staff user recording the payment (audited)
     * @throws ApiException VALIDATION_FAILED if method is not MANUAL or BANK_TRANSFER
     */
    @Transactional
    public BillingModels.PaymentView recordManualPayment(
            UUID tenantId, UUID staffId, BillingModels.ManualPaymentCommand command) {

        if (command.amountPaise() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "amountPaise must be positive");
        }

        String method = command.method() != null ? command.method().toUpperCase() : "MANUAL";
        if (!"MANUAL".equals(method) && !"BANK_TRANSFER".equals(method)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "method must be MANUAL or BANK_TRANSFER; got: " + command.method());
        }

        BillingModels.SubscriptionView sub = subscriptionService.getSubscription(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No subscription found for tenant: " + tenantId));

        BillingModels.InvoiceView invoice =
                invoiceService.createIssuedInvoice(tenantId, sub.id(), command.amountPaise());

        String currency = command.currency() != null ? command.currency().toUpperCase() : "INR";

        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO billing_payment (
                    id, tenant_id, invoice_id, subscription_id, method, status,
                    amount_paise, currency, external_reference, recorded_by_staff_id,
                    paid_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'CAPTURED', ?, ?, ?, ?, NOW(), NOW(), NOW())
                """,
                paymentId, tenantId, invoice.id(), sub.id(), method,
                command.amountPaise(), currency,
                command.externalReference(), staffId
        );

        invoiceService.markPaid(invoice.id());
        subscriptionService.applyPaidActivation(tenantId, sub.id());

        auditService.record(RsReachConstants.REALM_STAFF, staffId, tenantId, null,
                "billing.manual_payment_recorded", "payment", paymentId.toString(),
                "{\"method\":\"" + method + "\",\"amountPaise\":" + command.amountPaise() + "}");

        log.info("Manual payment recorded tenant={} staff={} payment={} amount={}",
                tenantId, staffId, paymentId, command.amountPaise());
        return getPaymentById(tenantId, paymentId);
    }

    // -----------------------------------------------------------------------
    // Razorpay payment application (idempotent)
    // -----------------------------------------------------------------------

    /**
     * Applies a captured Razorpay payment to the tenant's subscription.
     * Idempotent: if a payment with {@code providerPaymentId} already exists, returns it.
     * Called by {@link BillingWebhookService#applyEvent} after signature verification.
     *
     * @param providerPaymentId Razorpay pay_xxx id
     * @param providerOrderId   Razorpay order_xxx id (may be null for off-session events)
     * @param amountPaise       confirmed amount from the provider webhook payload
     */
    @Transactional
    public BillingModels.PaymentView applyRazorpayPayment(
            UUID tenantId, String providerPaymentId, String providerOrderId, long amountPaise) {

        // Idempotency check on provider_payment_id
        List<UUID> existingIds = jdbcTemplate.query(
                """
                SELECT id FROM billing_payment
                WHERE provider = 'razorpay' AND provider_payment_id = ?
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                providerPaymentId
        );
        if (!existingIds.isEmpty()) {
            log.info("applyRazorpayPayment: already processed providerPaymentId={}", providerPaymentId);
            return getPaymentById(tenantId, existingIds.get(0));
        }

        BillingModels.SubscriptionView sub = subscriptionService.getSubscription(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No subscription found for tenant: " + tenantId));

        UUID paymentId;
        UUID invoiceId;

        // Find an existing CREATED payment for this order (from checkout flow)
        List<UUID[]> byOrder = providerOrderId == null ? List.of()
                : jdbcTemplate.query(
                        """
                        SELECT bp.id AS payment_id, bp.invoice_id
                        FROM billing_payment bp
                        WHERE bp.tenant_id = ? AND bp.provider_order_id = ? AND bp.status = 'CREATED'
                        """,
                        (rs, i) -> new UUID[]{
                                (UUID) rs.getObject("payment_id"),
                                (UUID) rs.getObject("invoice_id")
                        },
                        tenantId, providerOrderId
                );

        if (!byOrder.isEmpty()) {
            UUID[] row = byOrder.get(0);
            paymentId = row[0];
            invoiceId = row[1];
            jdbcTemplate.update(
                    """
                    UPDATE billing_payment
                    SET status = 'CAPTURED', provider_payment_id = ?, amount_paise = ?,
                        paid_at = NOW(), updated_at = NOW()
                    WHERE id = ?
                    """,
                    providerPaymentId, amountPaise, paymentId
            );
        } else {
            // No prior order record — create a new payment and invoice (e.g. direct payment)
            BillingModels.InvoiceView invoice =
                    invoiceService.createIssuedInvoice(tenantId, sub.id(), amountPaise);
            invoiceId = invoice.id();
            paymentId = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    INSERT INTO billing_payment (
                        id, tenant_id, invoice_id, subscription_id, method, status,
                        amount_paise, currency, provider, provider_order_id, provider_payment_id,
                        paid_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'RAZORPAY', 'CAPTURED', ?, 'INR', 'razorpay', ?, ?, NOW(), NOW(), NOW())
                    """,
                    paymentId, tenantId, invoiceId, sub.id(), amountPaise,
                    providerOrderId, providerPaymentId
            );
        }

        invoiceService.markPaid(invoiceId);
        subscriptionService.applyPaidActivation(tenantId, sub.id());

        log.info("Razorpay payment applied tenant={} paymentId={} providerPaymentId={}",
                tenantId, paymentId, providerPaymentId);
        return getPaymentById(tenantId, paymentId);
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BillingModels.PaymentView> listByTenant(UUID tenantId) {
        return jdbcTemplate.query(
                """
                SELECT id, tenant_id, invoice_id, subscription_id, method, status,
                       amount_paise, currency, provider, provider_order_id, provider_payment_id,
                       external_reference, paid_at, created_at
                FROM billing_payment
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                """,
                (rs, i) -> mapPayment(rs),
                tenantId
        );
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    BillingModels.PaymentView getPaymentById(UUID tenantId, UUID paymentId) {
        List<BillingModels.PaymentView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, invoice_id, subscription_id, method, status,
                       amount_paise, currency, provider, provider_order_id, provider_payment_id,
                       external_reference, paid_at, created_at
                FROM billing_payment
                WHERE id = ? AND tenant_id = ?
                """,
                (rs, i) -> mapPayment(rs),
                paymentId, tenantId
        );
        return rows.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Payment not found: " + paymentId));
    }

    private static BillingModels.PaymentView mapPayment(ResultSet rs) throws SQLException {
        Timestamp paidAt = rs.getTimestamp("paid_at");
        return new BillingModels.PaymentView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("invoice_id"),
                (UUID) rs.getObject("subscription_id"),
                rs.getString("method"),
                rs.getString("status"),
                rs.getLong("amount_paise"),
                rs.getString("currency"),
                rs.getString("provider"),
                rs.getString("provider_order_id"),
                rs.getString("provider_payment_id"),
                rs.getString("external_reference"),
                paidAt == null ? null : paidAt.toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
