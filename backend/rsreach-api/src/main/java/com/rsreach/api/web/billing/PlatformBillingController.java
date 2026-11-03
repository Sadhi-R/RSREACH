package com.rsreach.api.web.billing;

import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.billing.InvoiceService;
import com.rsreach.billing.PaymentService;
import com.rsreach.billing.RefundService;
import com.rsreach.billing.SubscriptionService;
import com.rsreach.common.Permissions;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.security.auth.RsReachPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform (staff) billing endpoints for tenant management.
 * Requires PLATFORM_BILLING_MANAGE permission and MFA.
 */
@RestController
@RequestMapping("/api/v1/platform/billing")
public class PlatformBillingController {

    private final SubscriptionService subscriptionService;
    private final EntitlementService entitlementService;
    private final PaymentService paymentService;
    private final InvoiceService invoiceService;
    private final RefundService refundService;

    public PlatformBillingController(
            SubscriptionService subscriptionService,
            EntitlementService entitlementService,
            PaymentService paymentService,
            InvoiceService invoiceService,
            RefundService refundService
    ) {
        this.subscriptionService = subscriptionService;
        this.entitlementService = entitlementService;
        this.paymentService = paymentService;
        this.invoiceService = invoiceService;
        this.refundService = refundService;
    }

    // -----------------------------------------------------------------------
    // Tenant subscription + usage overview (read)
    // -----------------------------------------------------------------------

    @GetMapping("/tenants/{tenantId}")
    public Map<String, Object> getTenantBilling(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID tenantId
    ) {
        requirePermission(principal, Permissions.PLATFORM_BILLING_MANAGE);
        requireMfa(principal);
        Optional<BillingModels.SubscriptionView> sub = subscriptionService.getSubscription(tenantId);
        List<BillingModels.UsageMeterView> usage = sub.isPresent()
                ? entitlementService.currentUsage(tenantId)
                : List.of();
        return Map.of(
                "tenantId", tenantId,
                "subscription", sub.isPresent() ? sub.get() : Map.of(),
                "usage", usage
        );
    }

    // -----------------------------------------------------------------------
    // Manual payment recording
    // -----------------------------------------------------------------------

    @PostMapping("/tenants/{tenantId}/manual-payment")
    public BillingModels.PaymentView recordManualPayment(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID tenantId,
            @RequestBody BillingModels.ManualPaymentCommand command
    ) {
        requirePermission(principal, Permissions.PLATFORM_BILLING_MANAGE);
        requireMfa(principal);
        return paymentService.recordManualPayment(tenantId, principal.getId(), command);
    }

    // -----------------------------------------------------------------------
    // Entitlement overrides
    // -----------------------------------------------------------------------

    @PostMapping("/tenants/{tenantId}/entitlement-overrides")
    public Map<String, String> createEntitlementOverride(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> body
    ) {
        requirePermission(principal, Permissions.PLATFORM_BILLING_MANAGE);
        requireMfa(principal);
        String meterCode = str(body.get("meterCode"));
        Long limitValue = body.get("limitValue") == null ? null : Long.parseLong(body.get("limitValue").toString());
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        Instant expiresAt = body.get("expiresAt") == null ? null
                : Instant.parse(body.get("expiresAt").toString());

        entitlementService.createOverride(new BillingModels.OverrideCommand(
                tenantId, meterCode, limitValue, reason, expiresAt));
        return Map.of("status", "OK");
    }

    // -----------------------------------------------------------------------
    // Refunds
    // -----------------------------------------------------------------------

    /**
     * Records a refund against a payment for the given tenant.
     * Requires PLATFORM_BILLING_MANAGE permission and MFA.
     * The original invoice is NOT deleted.
     */
    @PostMapping("/tenants/{tenantId}/refunds")
    public BillingModels.RefundView recordRefund(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> body
    ) {
        requirePermission(principal, Permissions.PLATFORM_BILLING_MANAGE);
        requireMfa(principal);
        UUID paymentId = UUID.fromString(str(body.get("paymentId")));
        long amountPaise = Long.parseLong(body.get("amountPaise").toString());
        String reason = body.containsKey("reason") ? body.get("reason").toString() : null;
        return refundService.recordRefund(tenantId, paymentId, amountPaise, reason, principal.getId());
    }

    // -----------------------------------------------------------------------
    // Credit notes
    // -----------------------------------------------------------------------

    /**
     * Records a credit note against an invoice for the given tenant.
     * Requires PLATFORM_BILLING_MANAGE permission and MFA.
     * The original invoice is NOT deleted or mutated; the credit note is a separate document.
     */
    @PostMapping("/tenants/{tenantId}/credit-notes")
    public BillingModels.CreditNoteView recordCreditNote(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> body
    ) {
        requirePermission(principal, Permissions.PLATFORM_BILLING_MANAGE);
        requireMfa(principal);
        UUID invoiceId = UUID.fromString(str(body.get("invoiceId")));
        long amountPaise = Long.parseLong(body.get("amountPaise").toString());
        String reason = body.containsKey("reason") ? body.get("reason").toString() : null;
        return refundService.recordCreditNote(tenantId, invoiceId, amountPaise, reason);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void requirePermission(RsReachPrincipal principal, String permission) {
        if (!principal.hasPermission(permission)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing permission: " + permission);
        }
    }

    private void requireMfa(RsReachPrincipal principal) {
        if (!principal.isMfaVerified()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "MFA verification required for privileged staff actions");
        }
    }

    private static String str(Object value) {
        if (value == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Required field is missing");
        }
        return value.toString();
    }
}
