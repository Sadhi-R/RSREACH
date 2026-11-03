package com.rsreach.api.web.contacts;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.contacts.ContactMergeService;
import com.rsreach.contacts.MergeModels;
import com.rsreach.contacts.MergePreviewService;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class MergeController {

    private final MergePreviewService previewService;
    private final ContactMergeService mergeService;
    private final CustomerAuthorizationService authorizationService;

    public MergeController(
            MergePreviewService previewService,
            ContactMergeService mergeService,
            CustomerAuthorizationService authorizationService
    ) {
        this.previewService = previewService;
        this.mergeService = mergeService;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/contacts/merges/preview")
    public MergeModels.MergePreview preview(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody PreviewRequest request
    ) {
        Context ctx = requireMergeAuth(principal);
        return previewService.preview(
                ctx.tenantId(),
                ctx.workspaceId(),
                new MergeModels.PreviewCommand(
                        request.candidateId(),
                        request.survivorContactId(),
                        request.loserContactId(),
                        request.fieldChoices() == null ? List.of() : request.fieldChoices().stream()
                                .map(f -> new MergeModels.FieldChoice(f.fieldKey(), f.source()))
                                .toList(),
                        request.primaryChoices() == null ? List.of() : request.primaryChoices().stream()
                                .map(p -> new MergeModels.PrimaryChoice(p.channelType(), p.channelId()))
                                .toList()
                )
        );
    }

    @PostMapping("/contacts/merges")
    public ResponseEntity<MergeModels.MergeView> execute(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestHeader(value = RsReachConstants.HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ExecuteRequest request
    ) {
        Context ctx = requireMergeAuth(principal);
        MergeModels.MergeView view = mergeService.execute(
                ctx.tenantId(),
                ctx.workspaceId(),
                principal.getId(),
                idempotencyKey,
                new MergeModels.ExecuteCommand(
                        request.candidateId(),
                        request.survivorContactId(),
                        request.loserContactId(),
                        request.fieldChoices() == null ? List.of() : request.fieldChoices().stream()
                                .map(f -> new MergeModels.FieldChoice(f.fieldKey(), f.source()))
                                .toList(),
                        request.primaryChoices() == null ? List.of() : request.primaryChoices().stream()
                                .map(p -> new MergeModels.PrimaryChoice(p.channelType(), p.channelId()))
                                .toList(),
                        request.previewHash(),
                        request.confirm()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/contacts/merges")
    public MergeModels.MergePage history(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(required = false) UUID contactId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = requireRead(principal);
        return mergeService.history(ctx.tenantId(), ctx.workspaceId(), contactId, page, size);
    }

    @GetMapping("/contacts/merges/{mergeId}")
    public MergeModels.MergeView detail(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID mergeId
    ) {
        Context ctx = requireRead(principal);
        return mergeService.get(ctx.tenantId(), ctx.workspaceId(), mergeId);
    }

    private Context requireMergeAuth(RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_MANAGE);
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_CONSENT_MANAGE);
        return new Context(tenantId, workspaceId);
    }

    private Context requireRead(RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.CONTACTS_READ);
        return new Context(tenantId, workspaceId);
    }

    private record Context(UUID tenantId, UUID workspaceId) {
    }

    public record FieldChoiceRequest(String fieldKey, String source) {
    }

    public record PrimaryChoiceRequest(String channelType, UUID channelId) {
    }

    public record PreviewRequest(
            @NotNull UUID candidateId,
            @NotNull UUID survivorContactId,
            @NotNull UUID loserContactId,
            List<FieldChoiceRequest> fieldChoices,
            List<PrimaryChoiceRequest> primaryChoices
    ) {
    }

    public record ExecuteRequest(
            @NotNull UUID candidateId,
            @NotNull UUID survivorContactId,
            @NotNull UUID loserContactId,
            List<FieldChoiceRequest> fieldChoices,
            List<PrimaryChoiceRequest> primaryChoices,
            @NotNull String previewHash,
            boolean confirm
    ) {
    }
}
