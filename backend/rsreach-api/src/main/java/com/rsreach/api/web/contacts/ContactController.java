package com.rsreach.api.web.contacts;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.contacts.ContactModels;
import com.rsreach.contacts.ContactService;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
public class ContactController {

    private final ContactService contactService;
    private final CustomerAuthorizationService authorizationService;

    public ContactController(ContactService contactService, CustomerAuthorizationService authorizationService) {
        this.contactService = contactService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/contacts")
    public ContactModels.PageResult search(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = requireRead(principal);
        return contactService.search(ctx.tenantId(), ctx.workspaceId(), query, status, page, size);
    }

    @PostMapping("/contacts")
    public ResponseEntity<ContactModels.ContactView> create(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody ContactWriteRequest request
    ) {
        Context ctx = requireManage(principal);
        ContactModels.ContactView created = contactService.create(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                new ContactModels.ContactCreateCommand(request.displayName(), request.channels(), request.customFields())
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create(RsReachConstants.API_V1 + "/app/contacts/" + created.id()))
                .body(created);
    }

    @GetMapping("/contacts/{contactId}")
    public ContactModels.ContactView get(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId
    ) {
        Context ctx = requireRead(principal);
        return contactService.get(ctx.tenantId(), ctx.workspaceId(), contactId);
    }

    @PatchMapping("/contacts/{contactId}")
    public ContactModels.ContactView update(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId,
            @Valid @RequestBody ContactWriteRequest request
    ) {
        Context ctx = requireManage(principal);
        return contactService.update(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                contactId,
                new ContactModels.ContactUpdateCommand(request.displayName(), request.channels(), request.customFields())
        );
    }

    @PostMapping("/contacts/{contactId}/archive")
    public ContactModels.ContactView archive(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId
    ) {
        Context ctx = requireManage(principal);
        return contactService.archive(ctx.tenantId(), ctx.workspaceId(), principal.getId(), contactId);
    }

    @PostMapping("/contacts/{contactId}/restore")
    public ContactModels.ContactView restore(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID contactId
    ) {
        Context ctx = requireManage(principal);
        return contactService.restore(ctx.tenantId(), ctx.workspaceId(), principal.getId(), contactId);
    }

    @GetMapping("/contact-field-defs")
    public List<ContactModels.CustomFieldDef> listFieldDefs(@AuthenticationPrincipal RsReachPrincipal principal) {
        Context ctx = requireRead(principal);
        return contactService.listFieldDefs(ctx.tenantId(), ctx.workspaceId());
    }

    @PostMapping("/contact-field-defs")
    public ResponseEntity<ContactModels.CustomFieldDef> createFieldDef(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody FieldDefRequest request
    ) {
        Context ctx = requireManage(principal);
        ContactModels.CustomFieldDef created = contactService.createFieldDef(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                new ContactModels.CustomFieldDefCreateCommand(
                        request.fieldKey(), request.label(), request.dataType(), Boolean.TRUE.equals(request.required())
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
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

    public record ContactWriteRequest(
            @NotBlank String displayName,
            List<ContactModels.ChannelInput> channels,
            Map<String, Object> customFields
    ) {
    }

    public record FieldDefRequest(
            @NotBlank String fieldKey,
            @NotBlank String label,
            @NotBlank String dataType,
            Boolean required
    ) {
    }
}
