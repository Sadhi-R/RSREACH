package com.rsreach.api.web.comms;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.communications.AssignmentNoteService;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConversationService;
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
public class InboxController {

    private final ConversationService conversationService;
    private final AssignmentNoteService assignmentNoteService;
    private final CustomerAuthorizationService authorizationService;

    public InboxController(
            ConversationService conversationService,
            AssignmentNoteService assignmentNoteService,
            CustomerAuthorizationService authorizationService
    ) {
        this.conversationService = conversationService;
        this.assignmentNoteService = assignmentNoteService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/conversations")
    public CommunicationModels.ConversationPage list(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Context ctx = require(principal, Permissions.INBOX_READ);
        return conversationService.list(ctx.tenantId(), ctx.workspaceId(), channel, page, size);
    }

    @PostMapping("/conversations")
    public ResponseEntity<CommunicationModels.ConversationView> create(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @RequestBody CommunicationModels.CreateConversationCommand body
    ) {
        Context ctx = require(principal, Permissions.INBOX_REPLY);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                conversationService.create(ctx.tenantId(), ctx.workspaceId(), principal.getId(), body));
    }

    @GetMapping("/conversations/{conversationId}")
    public CommunicationModels.ConversationView get(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID conversationId
    ) {
        Context ctx = require(principal, Permissions.INBOX_READ);
        return conversationService.get(ctx.tenantId(), ctx.workspaceId(), conversationId);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public CommunicationModels.MessagePage messages(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        Context ctx = require(principal, Permissions.INBOX_READ);
        return conversationService.listMessages(ctx.tenantId(), ctx.workspaceId(), conversationId, page, size);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<CommunicationModels.MessageView> compose(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestBody CommunicationModels.ComposeMessageCommand body
    ) {
        Context ctx = require(principal, Permissions.INBOX_REPLY);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                conversationService.composeOutbound(
                        ctx.tenantId(), ctx.workspaceId(), principal.getId(), conversationId, body));
    }

    @PostMapping("/conversations/{conversationId}/assignment")
    public CommunicationModels.AssignmentView assign(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestBody Map<String, UUID> body
    ) {
        Context ctx = require(principal, Permissions.INBOX_ASSIGN);
        return assignmentNoteService.assign(
                ctx.tenantId(), ctx.workspaceId(), principal.getId(), conversationId, body.get("assigneeUserId"));
    }

    @DeleteMapping("/conversations/{conversationId}/assignment")
    public ResponseEntity<Void> unassign(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID conversationId
    ) {
        Context ctx = require(principal, Permissions.INBOX_ASSIGN);
        assignmentNoteService.unassign(ctx.tenantId(), ctx.workspaceId(), principal.getId(), conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{conversationId}/notes")
    public List<CommunicationModels.NoteView> notes(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID conversationId
    ) {
        Context ctx = require(principal, Permissions.INBOX_READ);
        return assignmentNoteService.listNotes(ctx.tenantId(), ctx.workspaceId(), conversationId);
    }

    @PostMapping("/conversations/{conversationId}/notes")
    public ResponseEntity<CommunicationModels.NoteView> addNote(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestBody Map<String, String> body
    ) {
        Context ctx = require(principal, Permissions.INBOX_REPLY);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                assignmentNoteService.addNote(
                        ctx.tenantId(), ctx.workspaceId(), principal.getId(), conversationId, body.get("bodyText")));
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
