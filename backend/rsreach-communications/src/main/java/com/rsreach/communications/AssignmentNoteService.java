package com.rsreach.communications;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentNoteService {

    private final JdbcTemplate jdbcTemplate;
    private final ConversationRepository conversationRepository;
    private final AuditService auditService;

    public AssignmentNoteService(
            JdbcTemplate jdbcTemplate,
            ConversationRepository conversationRepository,
            AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.conversationRepository = conversationRepository;
        this.auditService = auditService;
    }

    @Transactional
    public CommunicationModels.AssignmentView assign(
            UUID tenantId, UUID workspaceId, UUID actorId, UUID conversationId, UUID assigneeUserId
    ) {
        conversationRepository.findById(tenantId, workspaceId, conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conversation not found"));
        if (assigneeUserId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "assigneeUserId is required");
        }
        jdbcTemplate.update(
                """
                UPDATE conversation_assignments
                SET status = 'CLOSED', unassigned_at = NOW()
                WHERE tenant_id = ? AND workspace_id = ? AND conversation_id = ?
                  AND status = 'OPEN' AND unassigned_at IS NULL
                """,
                tenantId, workspaceId, conversationId
        );
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO conversation_assignments (
                    id, tenant_id, workspace_id, conversation_id, assignee_user_id,
                    assigned_by, status, assigned_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', NOW())
                """,
                id, tenantId, workspaceId, conversationId, assigneeUserId, actorId
        );
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "conversation.assigned", "conversation", conversationId.toString(),
                "{\"assigneeUserId\":\"" + assigneeUserId + "\"}"
        );
        return getOpenAssignment(tenantId, workspaceId, conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Assign failed"));
    }

    @Transactional
    public void unassign(UUID tenantId, UUID workspaceId, UUID actorId, UUID conversationId) {
        conversationRepository.findById(tenantId, workspaceId, conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conversation not found"));
        jdbcTemplate.update(
                """
                UPDATE conversation_assignments
                SET status = 'CLOSED', unassigned_at = NOW()
                WHERE tenant_id = ? AND workspace_id = ? AND conversation_id = ?
                  AND status = 'OPEN' AND unassigned_at IS NULL
                """,
                tenantId, workspaceId, conversationId
        );
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "conversation.unassigned", "conversation", conversationId.toString(), "{}"
        );
    }

    @Transactional(readOnly = true)
    public java.util.Optional<CommunicationModels.AssignmentView> getOpenAssignment(
            UUID tenantId, UUID workspaceId, UUID conversationId
    ) {
        List<CommunicationModels.AssignmentView> rows = jdbcTemplate.query(
                """
                SELECT id, conversation_id, assignee_user_id, assigned_by, status, assigned_at
                FROM conversation_assignments
                WHERE tenant_id = ? AND workspace_id = ? AND conversation_id = ?
                  AND status = 'OPEN' AND unassigned_at IS NULL
                """,
                (rs, i) -> new CommunicationModels.AssignmentView(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("conversation_id"),
                        (UUID) rs.getObject("assignee_user_id"),
                        (UUID) rs.getObject("assigned_by"),
                        rs.getString("status"),
                        rs.getTimestamp("assigned_at").toInstant()
                ),
                tenantId, workspaceId, conversationId
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public CommunicationModels.NoteView addNote(
            UUID tenantId, UUID workspaceId, UUID actorId, UUID conversationId, String bodyText
    ) {
        conversationRepository.findById(tenantId, workspaceId, conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conversation not found"));
        if (bodyText == null || bodyText.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "bodyText is required");
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO conversation_notes (
                    id, tenant_id, workspace_id, conversation_id, author_user_id, body_text, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, conversationId, actorId, bodyText.trim()
        );
        return listNotes(tenantId, workspaceId, conversationId).stream()
                .filter(n -> n.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Note create failed"));
    }

    @Transactional(readOnly = true)
    public List<CommunicationModels.NoteView> listNotes(UUID tenantId, UUID workspaceId, UUID conversationId) {
        conversationRepository.findById(tenantId, workspaceId, conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conversation not found"));
        return jdbcTemplate.query(
                """
                SELECT id, conversation_id, author_user_id, body_text, created_at
                FROM conversation_notes
                WHERE tenant_id = ? AND workspace_id = ? AND conversation_id = ?
                ORDER BY created_at DESC
                """,
                (rs, i) -> new CommunicationModels.NoteView(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("conversation_id"),
                        (UUID) rs.getObject("author_user_id"),
                        rs.getString("body_text"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                tenantId, workspaceId, conversationId
        );
    }
}
