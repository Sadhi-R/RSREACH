package com.rsreach.api.web.contacts;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.contacts.ContactShareModels;
import com.rsreach.contacts.ContactShareService;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class ContactShareController {

    private final ContactShareService shareService;
    private final CustomerAuthorizationService authorizationService;

    public ContactShareController(
            ContactShareService shareService,
            CustomerAuthorizationService authorizationService
    ) {
        this.shareService = shareService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/contacts/{contactId}/shares")
    public ContactShareModels.SharePage listContactShares(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId
    ) {
        Context ctx = requireManage(principal);
        return shareService.listContactShares(ctx.tenantId(), ctx.workspaceId(), contactId);
    }

    @PostMapping("/contacts/{contactId}/shares")
    public ResponseEntity<ContactShareModels.ShareView> shareContact(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId,
            @RequestBody ShareRequest request
    ) {
        Context ctx = requireManage(principal);
        ContactShareModels.ShareView created = shareService.shareContact(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                contactId,
                new ContactShareModels.CreateShareCommand(request.targetWorkspaceId(), request.permission())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/contacts/{contactId}/shares/{shareId}")
    public ResponseEntity<Void> revokeContactShare(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId,
            @PathVariable UUID shareId
    ) {
        Context ctx = requireManage(principal);
        shareService.revokeContactShare(
                ctx.tenantId(), ctx.workspaceId(), principal.getId(), contactId, shareId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/contact-groups/{groupId}/shares")
    public ContactShareModels.SharePage listGroupShares(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId
    ) {
        Context ctx = requireManage(principal);
        return shareService.listGroupShares(ctx.tenantId(), ctx.workspaceId(), groupId);
    }

    @PostMapping("/contact-groups/{groupId}/shares")
    public ResponseEntity<ContactShareModels.ShareView> shareGroup(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId,
            @RequestBody ShareRequest request
    ) {
        Context ctx = requireManage(principal);
        ContactShareModels.ShareView created = shareService.shareGroup(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                groupId,
                new ContactShareModels.CreateShareCommand(request.targetWorkspaceId(), request.permission())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/contact-groups/{groupId}/shares/{shareId}")
    public ResponseEntity<Void> revokeGroupShare(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId,
            @PathVariable UUID shareId
    ) {
        Context ctx = requireManage(principal);
        shareService.revokeGroupShare(
                ctx.tenantId(), ctx.workspaceId(), principal.getId(), groupId, shareId);
        return ResponseEntity.noContent().build();
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

    public record ShareRequest(UUID targetWorkspaceId, String permission) {
    }
}
