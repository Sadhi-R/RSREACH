-- Phase 5 M2: billing hardening — credit note sequential numbering.
-- billing_refund and credit_note tables were created in V27.
-- This migration seeds the credit note sequence row so RefundService can
-- allocate CN-YYYY-NNNNNN numbers with a row-level lock (same pattern as invoices).

INSERT INTO billing_invoice_seq (supplier_code, next_value)
VALUES ('CN-RSREACH', 1)
ON CONFLICT (supplier_code) DO NOTHING;
