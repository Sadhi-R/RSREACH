package com.rsreach.api.web.contacts;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.contacts.ContactGroupModels;
import com.rsreach.contacts.ContactGroupService;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class ContactGroupController {

    private final ContactGroupService contactGroupService;
    private final CustomerAuthorizationService authorizationService;

    public ContactGroupController(
            ContactGroupService contactGroupService,
            CustomerAuthorizationService authorizationService
    ) {
        this.contactGroupService = contactGroupService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/contact-groups")
    public ContactGroupModels.GroupPage search(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String groupType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = requireRead(principal);
        return contactGroupService.search(
                ctx.tenantId(), ctx.workspaceId(), query, status, groupType, page, size);
    }

    @PostMapping("/contact-groups")
    public ResponseEntity<ContactGroupModels.GroupView> create(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody GroupWriteRequest request
    ) {
        Context ctx = requireManage(principal);
        ContactGroupModels.GroupView created = contactGroupService.create(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                new ContactGroupModels.GroupCreateCommand(
                        request.name(), request.groupType(), request.filterDefinition())
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create(RsReachConstants.API_V1 + "/app/contact-groups/" + created.id()))
                .body(created);
    }

    @GetMapping("/contact-groups/{groupId}")
    public ContactGroupModels.GroupView get(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId
    ) {
        Context ctx = requireRead(principal);
        return contactGroupService.get(ctx.tenantId(), ctx.workspaceId(), groupId);
    }

    @PatchMapping("/contact-groups/{groupId}")
    public ContactGroupModels.GroupView update(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId,
            @Valid @RequestBody GroupUpdateRequest request
    ) {
        Context ctx = requireManage(principal);
        return contactGroupService.update(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                groupId,
                new ContactGroupModels.GroupUpdateCommand(request.name(), request.filterDefinition())
        );
    }

    @PostMapping("/contact-groups/{groupId}/archive")
    public ContactGroupModels.GroupView archive(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId
    ) {
        Context ctx = requireManage(principal);
        return contactGroupService.archive(ctx.tenantId(), ctx.workspaceId(), principal.getId(), groupId);
    }

    @GetMapping("/contact-groups/{groupId}/members")
    public ContactGroupModels.MemberPage members(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = requireRead(principal);
        return contactGroupService.listMembers(ctx.tenantId(), ctx.workspaceId(), groupId, page, size);
    }

    @PostMapping("/contact-groups/{groupId}/members")
    public ContactGroupModels.MembershipChangeResult addMembers(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId,
            @Valid @RequestBody MemberIdsRequest request
    ) {
        Context ctx = requireManage(principal);
        return contactGroupService.addMembers(
                ctx.tenantId(), ctx.workspaceId(), principal.getId(), groupId, request.contactIds());
    }

    @DeleteMapping("/contact-groups/{groupId}/members")
    public ContactGroupModels.MembershipChangeResult removeMembers(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId,
            @Valid @RequestBody MemberIdsRequest request
    ) {
        Context ctx = requireManage(principal);
        return contactGroupService.removeMembers(
                ctx.tenantId(), ctx.workspaceId(), principal.getId(), groupId, request.contactIds());
    }

    @PostMapping("/contact-groups/{groupId}/members/bulk")
    public ContactGroupModels.MembershipChangeResult bulkMembers(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId,
            @RequestBody BulkMembersRequest request
    ) {
        Context ctx = requireManage(principal);
        return contactGroupService.bulkMembers(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                groupId,
                request.addContactIds(),
                request.removeContactIds()
        );
    }

    @PostMapping("/contact-groups/{groupId}/refresh")
    public ContactGroupModels.GroupView refresh(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID groupId
    ) {
        Context ctx = requireManage(principal);
        return contactGroupService.requestRefresh(
                ctx.tenantId(), ctx.workspaceId(), principal.getId(), groupId);
    }

    @PostMapping("/contact-groups/preview")
    public ContactGroupModels.PreviewResult preview(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody PreviewRequest request
    ) {
        Context ctx = requireRead(principal);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 25 : request.size();
        return contactGroupService.preview(
                ctx.tenantId(), ctx.workspaceId(), request.filterDefinition(), page, size);
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

    public record GroupWriteRequest(
            @NotBlank String name,
            @NotBlank String groupType,
            Object filterDefinition
    ) {
    }

    public record GroupUpdateRequest(@NotBlank String name, Object filterDefinition) {
    }

    public record MemberIdsRequest(List<UUID> contactIds) {
    }

    public record BulkMembersRequest(List<UUID> addContactIds, List<UUID> removeContactIds) {
    }

    public record PreviewRequest(Object filterDefinition, Integer page, Integer size) {
    }
}
