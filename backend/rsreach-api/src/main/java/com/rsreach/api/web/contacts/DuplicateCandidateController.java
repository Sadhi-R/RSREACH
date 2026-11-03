package com.rsreach.api.web.contacts;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.contacts.DuplicateCandidateQueryService;
import com.rsreach.contacts.DuplicateCandidateService;
import com.rsreach.contacts.DuplicateModels;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class DuplicateCandidateController {

    private final DuplicateCandidateQueryService queryService;
    private final DuplicateCandidateService candidateService;
    private final CustomerAuthorizationService authorizationService;

    public DuplicateCandidateController(
            DuplicateCandidateQueryService queryService,
            DuplicateCandidateService candidateService,
            CustomerAuthorizationService authorizationService
    ) {
        this.queryService = queryService;
        this.candidateService = candidateService;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/contact-duplicates/scans")
    public ResponseEntity<DuplicateModels.ScanAccepted> scan(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        Context ctx = requireManage(principal);
        DuplicateModels.ScanAccepted accepted = candidateService.enqueueWorkspaceScan(
                ctx.tenantId(), ctx.workspaceId(), principal.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    @GetMapping("/contact-duplicates")
    public DuplicateModels.CandidatePage list(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID contactId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = requireRead(principal);
        return queryService.list(ctx.tenantId(), ctx.workspaceId(), status, contactId, page, size);
    }

    @GetMapping("/contact-duplicates/{candidateId}")
    public DuplicateModels.CandidateDetail get(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID candidateId
    ) {
        Context ctx = requireRead(principal);
        return queryService.get(ctx.tenantId(), ctx.workspaceId(), candidateId);
    }

    @PostMapping("/contact-duplicates/{candidateId}/dismiss")
    public DuplicateModels.CandidateDetail dismiss(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID candidateId,
            @Valid @RequestBody DismissRequest request
    ) {
        Context ctx = requireManage(principal);
        return candidateService.dismiss(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                candidateId,
                new DuplicateModels.DismissCommand(request.reason())
        );
    }

    @PostMapping("/contact-duplicates/{candidateId}/reopen")
    public DuplicateModels.CandidateDetail reopen(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID candidateId
    ) {
        Context ctx = requireManage(principal);
        return candidateService.reopen(ctx.tenantId(), ctx.workspaceId(), principal.getId(), candidateId);
    }

    private Context requireRead(RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_READ);
        return new Context(tenantId, workspaceId);
    }

    private Context requireManage(RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_MANAGE);
        return new Context(tenantId, workspaceId);
    }

    private record Context(UUID tenantId, UUID workspaceId) {
    }

    public record DismissRequest(@NotBlank String reason) {
    }
}
