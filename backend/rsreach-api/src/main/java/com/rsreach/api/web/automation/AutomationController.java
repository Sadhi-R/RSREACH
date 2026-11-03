package com.rsreach.api.web.automation;

import com.rsreach.automation.AutomationModels;
import com.rsreach.automation.AutomationWorkflowService;
import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing automation workflow endpoints under /api/v1/app/automations.
 * All operations enforce workspace isolation.
 */
@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app/automations")
public class AutomationController {

    private final AutomationWorkflowService workflowService;
    private final CustomerAuthorizationService authorizationService;

    public AutomationController(
            AutomationWorkflowService workflowService,
            CustomerAuthorizationService authorizationService
    ) {
        this.workflowService = workflowService;
        this.authorizationService = authorizationService;
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    @GetMapping
    public AutomationModels.WorkflowPage list(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_READ);
        return workflowService.list(ctx.tenantId(), ctx.workspaceId(), page, size);
    }

    @GetMapping("/{workflowId}")
    public AutomationModels.WorkflowDetail get(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID workflowId
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_READ);
        return workflowService.get(ctx.tenantId(), ctx.workspaceId(), workflowId);
    }

    @PostMapping
    public ResponseEntity<AutomationModels.WorkflowDetail> create(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody Map<String, Object> body
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_MANAGE);
        AutomationModels.CreateWorkflowCommand command = new AutomationModels.CreateWorkflowCommand(
                str(body.get("name")),
                str(body.get("triggerType")),
                strOrNull(body.get("triggerConfigJson")),
                strOrNull(body.get("conditionsJson")),
                strOrNull(body.get("actionsJson")),
                principal.getId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workflowService.create(ctx.tenantId(), ctx.workspaceId(), command));
    }

    @PutMapping("/{workflowId}")
    public AutomationModels.WorkflowDetail updateDraft(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID workflowId,
            @RequestBody Map<String, Object> body
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_MANAGE);
        AutomationModels.UpdateWorkflowDraftCommand command = new AutomationModels.UpdateWorkflowDraftCommand(
                str(body.get("name")),
                str(body.get("triggerType")),
                strOrNull(body.get("triggerConfigJson")),
                strOrNull(body.get("conditionsJson")),
                strOrNull(body.get("actionsJson"))
        );
        return workflowService.updateDraft(ctx.tenantId(), ctx.workspaceId(), workflowId, command);
    }

    // -----------------------------------------------------------------------
    // Lifecycle actions
    // -----------------------------------------------------------------------

    @PostMapping("/{workflowId}/activate")
    public AutomationModels.WorkflowDetail activate(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID workflowId
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_MANAGE);
        return workflowService.activate(ctx.tenantId(), ctx.workspaceId(), workflowId);
    }

    @PostMapping("/{workflowId}/pause")
    public AutomationModels.WorkflowDetail pause(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID workflowId
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_MANAGE);
        return workflowService.pause(ctx.tenantId(), ctx.workspaceId(), workflowId);
    }

    @PostMapping("/{workflowId}/archive")
    public AutomationModels.WorkflowDetail archive(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID workflowId
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_MANAGE);
        return workflowService.archive(ctx.tenantId(), ctx.workspaceId(), workflowId);
    }

    // -----------------------------------------------------------------------
    // Runs
    // -----------------------------------------------------------------------

    @GetMapping("/{workflowId}/runs")
    public AutomationModels.RunPage listRuns(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID workflowId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_READ);
        return workflowService.listRuns(ctx.tenantId(), ctx.workspaceId(), workflowId, page, size);
    }

    @GetMapping("/{workflowId}/runs/{runId}")
    public AutomationModels.RunView getRun(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID workflowId,
            @PathVariable UUID runId
    ) {
        Context ctx = require(principal, Permissions.AUTOMATIONS_READ);
        // verify workflow ownership first, then fetch run
        workflowService.get(ctx.tenantId(), ctx.workspaceId(), workflowId);
        return workflowService.getRun(ctx.tenantId(), ctx.workspaceId(), runId);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Context require(RsReachPrincipal principal, String permission) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, permission);
        return new Context(tenantId, workspaceId);
    }

    private static String str(Object value) {
        if (value == null) {
            throw new com.rsreach.common.error.ApiException(
                    com.rsreach.common.error.ErrorCode.VALIDATION_FAILED, "Required field is missing");
        }
        return value.toString();
    }

    private static String strOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private record Context(UUID tenantId, UUID workspaceId) {
    }
}
