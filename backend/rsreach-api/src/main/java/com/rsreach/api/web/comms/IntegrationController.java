package com.rsreach.api.web.comms;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConnectedAccountService;
import com.rsreach.communications.TemplateService;
import com.rsreach.email.MailboxSyncService;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import java.util.List;
import java.util.Map;
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
public class IntegrationController {

    private final ConnectedAccountService connectedAccountService;
    private final TemplateService templateService;
    private final MailboxSyncService mailboxSyncService;
    private final CustomerAuthorizationService authorizationService;

    public IntegrationController(
            ConnectedAccountService connectedAccountService,
            TemplateService templateService,
            MailboxSyncService mailboxSyncService,
            CustomerAuthorizationService authorizationService
    ) {
        this.connectedAccountService = connectedAccountService;
        this.templateService = templateService;
        this.mailboxSyncService = mailboxSyncService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/connected-accounts")
    public List<CommunicationModels.ConnectedAccountView> list(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        Context ctx = require(principal, Permissions.INTEGRATIONS_MANAGE);
        return connectedAccountService.list(ctx.tenantId(), ctx.workspaceId());
    }

    @PostMapping("/connected-accounts")
    public ResponseEntity<CommunicationModels.ConnectedAccountView> connect(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody CommunicationModels.CreateConnectedAccountCommand body
    ) {
        Context ctx = require(principal, Permissions.INTEGRATIONS_MANAGE);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                connectedAccountService.connect(ctx.tenantId(), ctx.workspaceId(), principal.getId(), body));
    }

    @DeleteMapping("/connected-accounts/{accountId}")
    public ResponseEntity<Void> disconnect(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID accountId
    ) {
        Context ctx = require(principal, Permissions.INTEGRATIONS_MANAGE);
        connectedAccountService.disconnect(ctx.tenantId(), ctx.workspaceId(), principal.getId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/connected-accounts/{accountId}/sync")
    public ResponseEntity<Map<String, String>> syncMailbox(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID accountId
    ) {
        Context ctx = require(principal, Permissions.INTEGRATIONS_MANAGE);
        connectedAccountService.get(ctx.tenantId(), ctx.workspaceId(), accountId);
        mailboxSyncService.enqueueSync(ctx.tenantId(), ctx.workspaceId(), accountId);
        return ResponseEntity.accepted().body(Map.of("status", "QUEUED"));
    }

    @PostMapping("/connected-accounts/{accountId}/oauth/start")
    public Map<String, String> oauthStart(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "gmail") String provider,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String redirectUri
    ) {
        Context ctx = require(principal, Permissions.INTEGRATIONS_MANAGE);
        connectedAccountService.get(ctx.tenantId(), ctx.workspaceId(), accountId);
        return connectedAccountService.oauthStart(provider, accountId, clientId, redirectUri);
    }

    @PostMapping("/connected-accounts/{accountId}/oauth/refresh")
    public ResponseEntity<Map<String, String>> oauthRefresh(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID accountId,
            @RequestBody Map<String, String> body
    ) {
        Context ctx = require(principal, Permissions.INTEGRATIONS_MANAGE);
        connectedAccountService.refreshAccessToken(
                ctx.tenantId(), ctx.workspaceId(), accountId, body.get("accessToken"));
        return ResponseEntity.ok(Map.of("status", "ROTATED"));
    }

    @GetMapping("/templates")
    public List<CommunicationModels.TemplateView> templates(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(required = false) String channel
    ) {
        Context ctx = require(principal, Permissions.TEMPLATES_MANAGE);
        return templateService.list(ctx.tenantId(), ctx.workspaceId(), channel);
    }

    @PostMapping("/templates")
    public ResponseEntity<CommunicationModels.TemplateView> upsertTemplate(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody Map<String, Object> body
    ) {
        Context ctx = require(principal, Permissions.TEMPLATES_MANAGE);
        UUID accountId = body.get("connectedAccountId") == null
                ? null
                : UUID.fromString(body.get("connectedAccountId").toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.upsert(
                ctx.tenantId(),
                ctx.workspaceId(),
                accountId,
                str(body.get("channel")),
                str(body.get("name")),
                str(body.get("language")),
                str(body.get("category")),
                str(body.get("status")),
                str(body.get("bodyPreview"))
        ));
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
