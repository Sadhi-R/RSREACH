package com.rsreach.api.web.contacts;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.contacts.ConsentModels;
import com.rsreach.contacts.SuppressionService;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class SuppressionController {

    private final SuppressionService suppressionService;
    private final CustomerAuthorizationService authorizationService;

    public SuppressionController(
            SuppressionService suppressionService,
            CustomerAuthorizationService authorizationService
    ) {
        this.suppressionService = suppressionService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/suppressions")
    public ConsentModels.SuppressionPage list(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = requireRead(principal);
        return suppressionService.list(ctx.tenantId(), ctx.workspaceId(), activeOnly, page, size);
    }

    @PostMapping("/suppressions")
    public ResponseEntity<ConsentModels.SuppressionView> create(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody SuppressionRequest request
    ) {
        Context ctx = requireConsentManage(principal);
        ConsentModels.SuppressionView created = suppressionService.create(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                new ConsentModels.SuppressionCreateCommand(
                        request.channelType(), request.value(), request.reason())
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create(RsReachConstants.API_V1 + "/app/suppressions/" + created.id()))
                .body(created);
    }

    @DeleteMapping("/suppressions/{suppressionId}")
    public ConsentModels.SuppressionView release(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID suppressionId
    ) {
        Context ctx = requireConsentManage(principal);
        return suppressionService.release(ctx.tenantId(), ctx.workspaceId(), principal.getId(), suppressionId);
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

    public record SuppressionRequest(
            @NotBlank String channelType,
            @NotBlank String value,
            @NotBlank String reason
    ) {
    }
}
