package com.rsreach.api.web.billing;

import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.billing.InvoiceService;
import com.rsreach.billing.PaymentService;
import com.rsreach.billing.PlanCatalogService;
import com.rsreach.billing.SubscriptionService;
import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing billing endpoints under /api/v1/app/billing.
 * All operations are scoped to the authenticated tenant; workspace context is not required.
 */
@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app/billing")
public class BillingController {

    private final SubscriptionService subscriptionService;
    private final PlanCatalogService planCatalogService;
    private final EntitlementService entitlementService;
    private final PaymentService paymentService;
    private final InvoiceService invoiceService;
    private final CustomerAuthorizationService authorizationService;

    public BillingController(
            SubscriptionService subscriptionService,
            PlanCatalogService planCatalogService,
            EntitlementService entitlementService,
            PaymentService paymentService,
            InvoiceService invoiceService,
            CustomerAuthorizationService authorizationService
    ) {
        this.subscriptionService = subscriptionService;
        this.planCatalogService = planCatalogService;
        this.entitlementService = entitlementService;
        this.paymentService = paymentService;
        this.invoiceService = invoiceService;
        this.authorizationService = authorizationService;
    }

    // -----------------------------------------------------------------------
    // Subscription
    // -----------------------------------------------------------------------

    @GetMapping("/subscription")
    public ResponseEntity<BillingModels.SubscriptionView> getSubscription(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.TENANT_BILLING_READ);
        return subscriptionService.getSubscription(tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // -----------------------------------------------------------------------
    // Plan catalog
    // -----------------------------------------------------------------------

    @GetMapping("/plans")
    public List<Map<String, Object>> listPlans(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        requireTenantPermission(principal, Permissions.TENANT_BILLING_READ);
        List<BillingModels.PlanView> plans = planCatalogService.listPlans();
        return plans.stream().map(plan -> {
            List<BillingModels.PlanVersionView> versions = planCatalogService.listVersionsForPlan(plan.id());
            List<Map<String, Object>> versionMaps = versions.stream().map(v -> {
                List<BillingModels.EntitlementView> entitlements = planCatalogService.listEntitlements(v.id());
                return Map.<String, Object>of(
                        "id", v.id(),
                        "versionNumber", v.versionNumber(),
                        "billingPeriod", v.billingPeriod(),
                        "pricePaise", v.pricePaise() != null ? v.pricePaise() : 0L,
                        "currency", v.currency() != null ? v.currency() : "INR",
                        "trialDays", v.trialDays(),
                        "entitlements", entitlements
                );
            }).collect(Collectors.toList());
            return Map.<String, Object>of(
                    "id", plan.id(),
                    "code", plan.code(),
                    "name", plan.name(),
                    "description", plan.description() != null ? plan.description() : "",
                    "sortOrder", plan.sortOrder(),
                    "active", plan.active(),
                    "versions", versionMaps
            );
        }).collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Lifecycle mutations
    // -----------------------------------------------------------------------

    @PostMapping("/trial")
    public BillingModels.SubscriptionView startTrial(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody Map<String, Object> body
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.TENANT_BILLING_MANAGE);
        UUID planVersionId = UUID.fromString(str(body.get("planVersionId")));
        return subscriptionService.startTrial(tenantId, planVersionId, principal.getId());
    }

    @PostMapping("/activate-free")
    public BillingModels.SubscriptionView activateFree(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.TENANT_BILLING_MANAGE);
        return subscriptionService.activateFree(tenantId, principal.getId());
    }

    @PostMapping("/cancel")
    public BillingModels.SubscriptionView cancel(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody Map<String, Object> body
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.TENANT_BILLING_MANAGE);
        boolean atPeriodEnd = Boolean.TRUE.equals(body.get("atPeriodEnd"));
        return subscriptionService.cancel(tenantId, principal.getId(), atPeriodEnd);
    }

    @PostMapping("/change-plan")
    public BillingModels.SubscriptionView changePlan(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody Map<String, Object> body
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.TENANT_BILLING_MANAGE);
        UUID planVersionId = UUID.fromString(str(body.get("planVersionId")));
        return subscriptionService.changePlan(tenantId, planVersionId, principal.getId());
    }

    @PostMapping("/checkout")
    public BillingModels.CheckoutResult checkout(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.TENANT_BILLING_MANAGE);
        return paymentService.checkout(tenantId, principal.getId());
    }

    // -----------------------------------------------------------------------
    // Usage
    // -----------------------------------------------------------------------

    @GetMapping("/usage")
    public List<BillingModels.UsageMeterView> currentUsage(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.TENANT_BILLING_READ);
        // Read-only: do not activate Free on GET. Explicit POST /activate-free required.
        if (subscriptionService.getSubscription(tenantId).isEmpty()) {
            return List.of();
        }
        return entitlementService.currentUsage(tenantId);
    }

    // -----------------------------------------------------------------------
    // Invoices
    // -----------------------------------------------------------------------

    @GetMapping("/invoices")
    public List<BillingModels.InvoiceView> listInvoices(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.BILLING_INVOICES_READ);
        return invoiceService.listByTenant(tenantId);
    }

    @GetMapping("/invoices/{invoiceId}")
    public BillingModels.InvoiceView getInvoice(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID invoiceId
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.BILLING_INVOICES_READ);
        return invoiceService.getById(tenantId, invoiceId);
    }

    // -----------------------------------------------------------------------
    // Payments
    // -----------------------------------------------------------------------

    @GetMapping("/payments")
    public List<BillingModels.PaymentView> listPayments(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        UUID tenantId = requireTenantPermission(principal, Permissions.BILLING_INVOICES_READ);
        return paymentService.listByTenant(tenantId);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private UUID requireTenantPermission(RsReachPrincipal principal, String permission) {
        UUID tenantId = TenantContext.requireTenantId();
        authorizationService.requireTenantPermission(principal.getId(), tenantId, permission);
        return tenantId;
    }

    private static String str(Object value) {
        if (value == null) {
            throw new com.rsreach.common.error.ApiException(
                    com.rsreach.common.error.ErrorCode.VALIDATION_FAILED, "Required field is missing");
        }
        return value.toString();
    }
}
