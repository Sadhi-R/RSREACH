package com.rsreach.api.web.comms;

import com.rsreach.campaigns.CampaignModels;
import com.rsreach.campaigns.CampaignService;
import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import java.time.Instant;
import java.util.Map;
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
public class CampaignController {

    private final CampaignService campaignService;
    private final CustomerAuthorizationService authorizationService;

    public CampaignController(CampaignService campaignService, CustomerAuthorizationService authorizationService) {
        this.campaignService = campaignService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/campaigns")
    public CampaignModels.CampaignPage list(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = require(principal, Permissions.CAMPAIGNS_READ);
        return campaignService.list(ctx.tenantId(), ctx.workspaceId(), page, size);
    }

    @PostMapping("/campaigns")
    public ResponseEntity<CampaignModels.CampaignView> create(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody Map<String, Object> body
    ) {
        Context ctx = require(principal, Permissions.CAMPAIGNS_MANAGE);
        Instant scheduledAt = body.get("scheduledAt") == null
                ? null
                : Instant.parse(body.get("scheduledAt").toString());
        UUID groupId = body.get("audienceGroupId") == null
                ? null
                : UUID.fromString(body.get("audienceGroupId").toString());
        CampaignModels.CreateCampaignCommand command = new CampaignModels.CreateCampaignCommand(
                str(body.get("name")),
                str(body.get("channel")),
                str(body.get("purpose")),
                groupId,
                str(body.get("bodyText")),
                scheduledAt,
                str(body.get("timezone"))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(
                campaignService.create(ctx.tenantId(), ctx.workspaceId(), principal.getId(), command));
    }

    @GetMapping("/campaigns/{campaignId}")
    public CampaignModels.CampaignView get(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID campaignId
    ) {
        Context ctx = require(principal, Permissions.CAMPAIGNS_READ);
        return campaignService.get(ctx.tenantId(), ctx.workspaceId(), campaignId);
    }

    @GetMapping("/campaigns/{campaignId}/stats")
    public CampaignModels.RecipientStats stats(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID campaignId
    ) {
        Context ctx = require(principal, Permissions.CAMPAIGNS_READ);
        return campaignService.stats(ctx.tenantId(), ctx.workspaceId(), campaignId);
    }

    @PostMapping("/campaigns/{campaignId}/start")
    public CampaignModels.CampaignView start(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID campaignId
    ) {
        Context ctx = require(principal, Permissions.CAMPAIGNS_SEND);
        return campaignService.start(ctx.tenantId(), ctx.workspaceId(), principal.getId(), campaignId);
    }

    @PostMapping("/campaigns/{campaignId}/pause")
    public CampaignModels.CampaignView pause(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID campaignId
    ) {
        Context ctx = require(principal, Permissions.CAMPAIGNS_SEND);
        return campaignService.pause(ctx.tenantId(), ctx.workspaceId(), principal.getId(), campaignId);
    }

    @PostMapping("/campaigns/{campaignId}/cancel")
    public CampaignModels.CampaignView cancel(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID campaignId
    ) {
        Context ctx = require(principal, Permissions.CAMPAIGNS_SEND);
        return campaignService.cancel(ctx.tenantId(), ctx.workspaceId(), principal.getId(), campaignId);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private Context require(RsReachPrincipal principal, String permission) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, permission);
        return new Context(tenantId, workspaceId);
    }

    private record Context(UUID tenantId, UUID workspaceId) {
    }
}
