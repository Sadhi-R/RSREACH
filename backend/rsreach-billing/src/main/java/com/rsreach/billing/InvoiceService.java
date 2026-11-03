package com.rsreach.billing;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice creation and retrieval.
 *
 * <p>Invoice numbers are unique and monotonically increasing via {@code billing_invoice_seq}
 * with a {@code FOR UPDATE} row-level lock so concurrent inserts never duplicate a number.
 *
 * <p>Invoices are never deleted. Refunds create credit_note rows; voids set status VOID.
 * GST fields (cgst_paise, sgst_paise, igst_paise, place_of_supply) are stored as-is; no
 * GST rate is inferred or hardcoded in code.
 */
@Service
public class InvoiceService {

    private static final String SUPPLIER_CODE = "RSREACH";
    /** Invoices are due 30 days from issuance by default. */
    private static final long DEFAULT_DUE_DAYS = 30L;

    private final JdbcTemplate jdbcTemplate;

    public InvoiceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -----------------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------------

    /**
     * Creates an ISSUED invoice for the given tenant and amount.
     * Allocates the next sequential invoice number under a row lock.
     *
     * @param totalPaise amount in integer paise; must be non-negative
     */
    @Transactional
    public BillingModels.InvoiceView createIssuedInvoice(
            UUID tenantId, UUID subscriptionId, long totalPaise) {
        if (totalPaise < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Invoice total must be non-negative");
        }

        // Allocate next number atomically
        Long seqValue = jdbcTemplate.queryForObject(
                "SELECT next_value FROM billing_invoice_seq WHERE supplier_code = ? FOR UPDATE",
                Long.class, SUPPLIER_CODE
        );
        if (seqValue == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "billing_invoice_seq row missing for " + SUPPLIER_CODE);
        }
        jdbcTemplate.update(
                "UPDATE billing_invoice_seq SET next_value = next_value + 1 WHERE supplier_code = ?",
                SUPPLIER_CODE
        );

        int year = Year.now(ZoneOffset.UTC).getValue();
        String invoiceNumber = String.format("INV-%d-%06d", year, seqValue);

        UUID id = UUID.randomUUID();
        Instant dueAt = Instant.now().plus(DEFAULT_DUE_DAYS, ChronoUnit.DAYS);

        jdbcTemplate.update(
                """
                INSERT INTO billing_invoice (
                    id, tenant_id, subscription_id, invoice_number, status, currency,
                    subtotal_paise, tax_paise, total_paise,
                    cgst_paise, sgst_paise, igst_paise,
                    issued_at, due_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ISSUED', 'INR', ?, 0, ?, 0, 0, 0, NOW(), ?, NOW(), NOW())
                """,
                id, tenantId, subscriptionId, invoiceNumber,
                totalPaise, totalPaise,
                Timestamp.from(dueAt)
        );

        return getById(tenantId, id);
    }

    // -----------------------------------------------------------------------
    // Status transitions
    // -----------------------------------------------------------------------

    @Transactional
    public void markPaid(UUID invoiceId) {
        jdbcTemplate.update(
                "UPDATE billing_invoice SET status = 'PAID', updated_at = NOW() WHERE id = ?",
                invoiceId
        );
    }

    @Transactional
    public void markVoid(UUID invoiceId) {
        jdbcTemplate.update(
                "UPDATE billing_invoice SET status = 'VOID', updated_at = NOW() WHERE id = ?",
                invoiceId
        );
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BillingModels.InvoiceView> listByTenant(UUID tenantId) {
        return jdbcTemplate.query(
                """
                SELECT id, tenant_id, subscription_id, invoice_number, status, currency,
                       subtotal_paise, tax_paise, total_paise,
                       period_start, period_end, issued_at, due_at, created_at
                FROM billing_invoice
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                """,
                (rs, i) -> mapInvoice(rs),
                tenantId
        );
    }

    @Transactional(readOnly = true)
    public BillingModels.InvoiceView getById(UUID tenantId, UUID invoiceId) {
        List<BillingModels.InvoiceView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, subscription_id, invoice_number, status, currency,
                       subtotal_paise, tax_paise, total_paise,
                       period_start, period_end, issued_at, due_at, created_at
                FROM billing_invoice
                WHERE id = ? AND tenant_id = ?
                """,
                (rs, i) -> mapInvoice(rs),
                invoiceId, tenantId
        );
        return rows.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Invoice not found: " + invoiceId));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static BillingModels.InvoiceView mapInvoice(ResultSet rs) throws SQLException {
        Timestamp periodStart = rs.getTimestamp("period_start");
        Timestamp periodEnd = rs.getTimestamp("period_end");
        Timestamp issuedAt = rs.getTimestamp("issued_at");
        Timestamp dueAt = rs.getTimestamp("due_at");
        return new BillingModels.InvoiceView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("subscription_id"),
                rs.getString("invoice_number"),
                rs.getString("status"),
                rs.getString("currency"),
                rs.getLong("subtotal_paise"),
                rs.getLong("tax_paise"),
                rs.getLong("total_paise"),
                periodStart == null ? null : periodStart.toInstant(),
                periodEnd == null ? null : periodEnd.toInstant(),
                issuedAt == null ? null : issuedAt.toInstant(),
                dueAt == null ? null : dueAt.toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
