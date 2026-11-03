package com.rsreach.api.web.contacts;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.contacts.ConsentEvaluationService;
import com.rsreach.contacts.ConsentModels;
import com.rsreach.contacts.ConsentService;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class ConsentController {

    private final ConsentService consentService;
    private final ConsentEvaluationService evaluationService;
    private final CustomerAuthorizationService authorizationService;

    public ConsentController(
            ConsentService consentService,
            ConsentEvaluationService evaluationService,
            CustomerAuthorizationService authorizationService
    ) {
        this.consentService = consentService;
        this.evaluationService = evaluationService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/contacts/{contactId}/consents")
    public ConsentModels.ConsentMatrix getConsents(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId
    ) {
        Context ctx = requireRead(principal);
        return consentService.getOrSeedMatrix(ctx.tenantId(), ctx.workspaceId(), contactId, principal.getId());
    }

    @GetMapping("/contacts/{contactId}/consents/history")
    public ConsentModels.ConsentHistoryPage history(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = requireRead(principal);
        return consentService.history(ctx.tenantId(), ctx.workspaceId(), contactId, page, size);
    }

    @PutMapping("/contacts/{contactId}/consents")
    public ConsentModels.ConsentView putConsent(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId,
            @Valid @RequestBody ConsentChangeRequest request
    ) {
        Context ctx = requireConsentManage(principal);
        return consentService.change(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                contactId,
                new ConsentModels.ConsentChangeCommand(
                        request.channelType(),
                        request.purpose(),
                        request.status(),
                        request.source(),
                        request.evidenceNote()
                )
        );
    }

    @PostMapping("/contacts/{contactId}/consent-eligibility")
    public ConsentModels.EligibilityResult evaluate(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId,
            @Valid @RequestBody EligibilityRequest request
    ) {
        Context ctx = requireRead(principal);
        return evaluationService.evaluate(
                ctx.tenantId(),
                ctx.workspaceId(),
                contactId,
                request.channelType(),
                request.purpose(),
                request.address()
        );
    }

    private Context requireRead(RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_READ);
        return new Context(tenantId, workspaceId);
    }

    private Context requireConsentManage(RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_CONSENT_MANAGE);
        return new Context(tenantId, workspaceId);
    }

    private record Context(UUID tenantId, UUID workspaceId) {
    }

    public record ConsentChangeRequest(
            @NotBlank String channelType,
            @NotBlank String purpose,
            @NotBlank String status,
            String source,
            String evidenceNote
    ) {
    }

    /**
     * Explicit recipient address is required. Do not omit address and expect primary fallback.
     */
    public record EligibilityRequest(
            @NotBlank String channelType,
            @NotBlank String purpose,
            @NotBlank String address
    ) {
    }
}
