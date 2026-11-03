package com.rsreach.communications;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateService {

    private final JdbcTemplate jdbcTemplate;

    public TemplateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<CommunicationModels.TemplateView> list(UUID tenantId, UUID workspaceId, String channel) {
        boolean filter = channel != null && !channel.isBlank();
        return jdbcTemplate.query(
                """
                SELECT id, channel, name, language, category, status, body_preview
                FROM channel_templates
                WHERE tenant_id = ? AND workspace_id = ?
                  AND (? = FALSE OR channel = ?)
                ORDER BY name
                """,
                (rs, i) -> new CommunicationModels.TemplateView(
                        (UUID) rs.getObject("id"),
                        rs.getString("channel"),
                        rs.getString("name"),
                        rs.getString("language"),
                        rs.getString("category"),
                        rs.getString("status"),
                        rs.getString("body_preview")
                ),
                tenantId, workspaceId, filter, filter ? channel.trim().toUpperCase(Locale.ROOT) : ""
        );
    }

    @Transactional
    public CommunicationModels.TemplateView upsert(
            UUID tenantId,
            UUID workspaceId,
            UUID connectedAccountId,
            String channel,
            String name,
            String language,
            String category,
            String status,
            String bodyPreview
    ) {
        if (name == null || name.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name is required");
        }
        String ch = channel == null ? "WHATSAPP" : channel.trim().toUpperCase(Locale.ROOT);
        String lang = language == null || language.isBlank() ? "en" : language.trim();
        String st = status == null || status.isBlank() ? "PENDING" : status.trim().toUpperCase(Locale.ROOT);
        List<UUID> existing = jdbcTemplate.query(
                """
                SELECT id FROM channel_templates
                WHERE tenant_id = ? AND workspace_id = ? AND channel = ? AND name = ? AND language = ?
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId, ch, name.trim(), lang
        );
        UUID id;
        if (existing.isEmpty()) {
            id = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    INSERT INTO channel_templates (
                        id, tenant_id, workspace_id, connected_account_id, channel, name, language,
                        category, status, body_preview, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                    """,
                    id, tenantId, workspaceId, connectedAccountId, ch, name.trim(), lang,
                    category, st, bodyPreview
            );
        } else {
            id = existing.get(0);
            jdbcTemplate.update(
                    """
                    UPDATE channel_templates
                    SET category = ?, status = ?, body_preview = ?, connected_account_id = COALESCE(?, connected_account_id),
                        updated_at = NOW()
                    WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                    """,
                    category, st, bodyPreview, connectedAccountId, id, tenantId, workspaceId
            );
        }
        return list(tenantId, workspaceId, ch).stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Template upsert failed"));
    }
}
