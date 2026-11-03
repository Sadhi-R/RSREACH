package com.rsreach.billing;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records refunds and credit notes for billing adjustments.
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>Refunds reference an existing payment that belongs to the same tenant.
 *   <li>Credit notes reference an existing invoice that belongs to the same tenant.
 *   <li>Neither a refund nor a credit note deletes or mutates the original invoice row.
 *   <li>Credit note numbers are sequential: {@code CN-YYYY-NNNNNN}, allocated under a
 *       row-level lock on {@code billing_invoice_seq} (same mechanism as invoice numbers).
 *   <li>Every refund is audited with the staff actor.
 * </ul>
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final String CN_SUPPLIER_CODE = "CN-RSREACH";

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public RefundService(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    // -----------------------------------------------------------------------
    // Refunds
    // -----------------------------------------------------------------------

    /**
     * Records a refund against an existing payment.
     * The original invoice and payment rows are never deleted or mutated.
     *
     * @param tenantId   owning tenant (must match the payment's tenant_id)
     * @param paymentId  UUID of the {@code billing_payment} row being refunded
     * @param amountPaise amount to refund in integer paise; must be positive
     * @param reason     free-text reason for the refund (may be null)
     * @param staffId    staff actor performing the action (audited)
     * @throws ApiException NOT_FOUND if the payment does not belong to this tenant
     * @throws ApiException VALIDATION_FAILED if amountPaise ≤ 0
     */
    @Transactional
    public BillingModels.RefundView recordRefund(
            UUID tenantId,
            UUID paymentId,
            long amountPaise,
            String reason,
            UUID staffId) {

        if (amountPaise <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "amountPaise must be positive");
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM billing_payment WHERE id = ? AND tenant_id = ?",
                Integer.class, paymentId, tenantId);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Payment not found: " + paymentId);
        }

        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO billing_refund
                    (id, tenant_id, payment_id, amount_paise, currency, reason, status, created_at)
                VALUES (?, ?, ?, ?, 'INR', ?, 'RECORDED', NOW())
                """,
                id, tenantId, paymentId, amountPaise, reason
        );

        auditService.record(
                RsReachConstants.REALM_STAFF, staffId, tenantId, null,
                "billing.refund_recorded", "refund", id.toString(),
                "{\"paymentId\":\"" + paymentId + "\",\"amountPaise\":" + amountPaise + "}"
        );

        log.info("Refund recorded tenant={} payment={} refund={} amount={}",
                tenantId, paymentId, id, amountPaise);
        return getRefundById(tenantId, id);
    }

    // -----------------------------------------------------------------------
    // Credit notes
    // -----------------------------------------------------------------------

    /**
     * Records a credit note against an existing invoice.
     * The original invoice is not mutated; the credit note is a separate document.
     * Credit note numbers are allocated under a row-level lock (CN-YYYY-NNNNNN).
     *
     * @param tenantId   owning tenant (must match the invoice's tenant_id)
     * @param invoiceId  UUID of the {@code billing_invoice} row
     * @param amountPaise credit amount in integer paise; must be positive
     * @param reason     free-text reason (may be null)
     * @throws ApiException NOT_FOUND if the invoice does not belong to this tenant
     * @throws ApiException VALIDATION_FAILED if amountPaise ≤ 0
     */
    @Transactional
    public BillingModels.CreditNoteView recordCreditNote(
            UUID tenantId,
            UUID invoiceId,
            long amountPaise,
            String reason) {

        if (amountPaise <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "amountPaise must be positive");
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM billing_invoice WHERE id = ? AND tenant_id = ?",
                Integer.class, invoiceId, tenantId);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Invoice not found: " + invoiceId);
        }

        // Allocate next CN number atomically under a row lock
        Long seqValue = jdbcTemplate.queryForObject(
                "SELECT next_value FROM billing_invoice_seq WHERE supplier_code = ? FOR UPDATE",
                Long.class, CN_SUPPLIER_CODE);
        if (seqValue == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "Credit note sequence row missing for " + CN_SUPPLIER_CODE);
        }
        jdbcTemplate.update(
                "UPDATE billing_invoice_seq SET next_value = next_value + 1 WHERE supplier_code = ?",
                CN_SUPPLIER_CODE
        );

        int year = Year.now(ZoneOffset.UTC).getValue();
        String creditNoteNumber = String.format("CN-%d-%06d", year, seqValue);

        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO credit_note
                    (id, tenant_id, invoice_id, credit_note_number, amount_paise, currency, reason, created_at)
                VALUES (?, ?, ?, ?, ?, 'INR', ?, NOW())
                """,
                id, tenantId, invoiceId, creditNoteNumber, amountPaise, reason
        );

        log.info("Credit note recorded tenant={} invoice={} cn={} amount={}",
                tenantId, invoiceId, creditNoteNumber, amountPaise);
        return getCreditNoteById(tenantId, id);
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BillingModels.RefundView getRefundById(UUID tenantId, UUID refundId) {
        List<BillingModels.RefundView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, payment_id, amount_paise, currency,
                       reason, provider_refund_id, status, created_at
                FROM billing_refund
                WHERE id = ? AND tenant_id = ?
                """,
                (rs, i) -> mapRefund(rs),
                refundId, tenantId
        );
        return rows.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Refund not found: " + refundId));
    }

    @Transactional(readOnly = true)
    public BillingModels.CreditNoteView getCreditNoteById(UUID tenantId, UUID creditNoteId) {
        List<BillingModels.CreditNoteView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, invoice_id, credit_note_number,
                       amount_paise, currency, reason, created_at
                FROM credit_note
                WHERE id = ? AND tenant_id = ?
                """,
                (rs, i) -> mapCreditNote(rs),
                creditNoteId, tenantId
        );
        return rows.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Credit note not found: " + creditNoteId));
    }

    // -----------------------------------------------------------------------
    // Mappers
    // -----------------------------------------------------------------------

    private static BillingModels.RefundView mapRefund(ResultSet rs) throws SQLException {
        return new BillingModels.RefundView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("payment_id"),
                rs.getLong("amount_paise"),
                rs.getString("currency"),
                rs.getString("reason"),
                rs.getString("provider_refund_id"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static BillingModels.CreditNoteView mapCreditNote(ResultSet rs) throws SQLException {
        return new BillingModels.CreditNoteView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("invoice_id"),
                rs.getString("credit_note_number"),
                rs.getLong("amount_paise"),
                rs.getString("currency"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
