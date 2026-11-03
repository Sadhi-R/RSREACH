package com.rsreach.contacts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConsentRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public ConsentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ConsentModels.ConsentView> listConsents(UUID tenantId, UUID workspaceId, UUID contactId) {
        return jdbcTemplate.query(
                """
                SELECT id, workspace_id, contact_id, channel_type, purpose, status, source, evidence::text,
                       captured_at, updated_at, updated_by
                FROM contact_consents
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                ORDER BY channel_type, purpose
                """,
                (rs, i) -> mapConsent(rs),
                tenantId, workspaceId, contactId
        );
    }

    public Optional<ConsentModels.ConsentView> findConsent(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose
    ) {
        List<ConsentModels.ConsentView> rows = jdbcTemplate.query(
                """
                SELECT id, workspace_id, contact_id, channel_type, purpose, status, source, evidence::text,
                       captured_at, updated_at, updated_by
                FROM contact_consents
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                  AND channel_type = ? AND purpose = ?
                FOR UPDATE
                """,
                (rs, i) -> mapConsent(rs),
                tenantId, workspaceId, contactId, channelType, purpose
        );
        return rows.stream().findFirst();
    }

    public UUID insertConsent(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose,
            String status,
            String source,
            String evidenceJson,
            UUID actorId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_consents (
                    id, tenant_id, workspace_id, contact_id, channel_type, purpose,
                    status, source, evidence, captured_at, updated_at, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), NOW(), NOW(), ?)
                """,
                id, tenantId, workspaceId, contactId, channelType, purpose,
                status, source, evidenceJson, actorId
        );
        return id;
    }

    /**
     * Idempotent UNKNOWN seed. Returns the new id when inserted, empty when the row already existed.
     */
    public Optional<UUID> insertUnknownIfAbsent(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose,
            UUID actorId
    ) {
        UUID id = UUID.randomUUID();
        List<UUID> inserted = jdbcTemplate.query(
                """
                INSERT INTO contact_consents (
                    id, tenant_id, workspace_id, contact_id, channel_type, purpose,
                    status, source, evidence, captured_at, updated_at, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'UNKNOWN', 'API', '{}'::jsonb, NOW(), NOW(), ?)
                ON CONFLICT (tenant_id, workspace_id, contact_id, channel_type, purpose) DO NOTHING
                RETURNING id
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                id, tenantId, workspaceId, contactId, channelType, purpose, actorId
        );
        return inserted.stream().findFirst();
    }

    public void updateConsent(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose,
            String status,
            String source,
            String evidenceJson,
            UUID actorId
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_consents
                SET status = ?, source = ?, evidence = CAST(? AS jsonb),
                    updated_at = NOW(), updated_by = ?
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                  AND channel_type = ? AND purpose = ?
                """,
                status, source, evidenceJson, actorId,
                tenantId, workspaceId, contactId, channelType, purpose
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Consent record not found");
        }
    }

    public void insertEvent(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose,
            String previousStatus,
            String newStatus,
            String eventType,
            String source,
            String evidenceJson,
            UUID actorId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO contact_consent_events (
                    id, tenant_id, workspace_id, contact_id, channel_type, purpose,
                    previous_status, new_status, event_type, source, evidence, actor_id, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, NOW())
                """,
                UUID.randomUUID(), tenantId, workspaceId, contactId, channelType, purpose,
                previousStatus, newStatus, eventType, source, evidenceJson, actorId
        );
    }

    public ConsentModels.ConsentHistoryPage listEvents(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            int page,
            int size
    ) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        List<ConsentModels.ConsentEventView> items = jdbcTemplate.query(
                """
                SELECT id, workspace_id, contact_id, channel_type, purpose, previous_status, new_status,
                       event_type, source, evidence::text, actor_id, occurred_at
                FROM contact_consent_events
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                ORDER BY occurred_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> new ConsentModels.ConsentEventView(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("workspace_id"),
                        (UUID) rs.getObject("contact_id"),
                        rs.getString("channel_type"),
                        rs.getString("purpose"),
                        rs.getString("previous_status"),
                        rs.getString("new_status"),
                        rs.getString("event_type"),
                        rs.getString("source"),
                        parseJson(rs.getString("evidence")),
                        (UUID) rs.getObject("actor_id"),
                        rs.getTimestamp("occurred_at").toInstant()
                ),
                tenantId, workspaceId, contactId, limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new ConsentModels.ConsentHistoryPage(items, page, limit, hasNext);
    }

    public UUID insertSuppression(
            UUID tenantId,
            UUID workspaceId,
            String channelType,
            String valueRaw,
            String valueNormalized,
            String reason,
            UUID actorId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_suppressions (
                    id, tenant_id, workspace_id, channel_type, value_raw, value_normalized,
                    reason, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                id, tenantId, workspaceId, channelType, valueRaw, valueNormalized, reason, actorId
        );
        return id;
    }

    public Optional<ConsentModels.SuppressionView> findActiveSuppression(
            UUID tenantId,
            UUID workspaceId,
            String channelType,
            String valueNormalized
    ) {
        List<ConsentModels.SuppressionView> rows = jdbcTemplate.query(
                """
                SELECT id, channel_type, value_raw, value_normalized, reason,
                       created_at, released_at, created_by, released_by
                FROM contact_suppressions
                WHERE tenant_id = ? AND workspace_id = ? AND channel_type = ?
                  AND value_normalized = ? AND released_at IS NULL
                """,
                (rs, i) -> mapSuppression(rs),
                tenantId, workspaceId, channelType, valueNormalized
        );
        return rows.stream().findFirst();
    }

    public Optional<ConsentModels.SuppressionView> findSuppressionById(
            UUID tenantId,
            UUID workspaceId,
            UUID suppressionId
    ) {
        List<ConsentModels.SuppressionView> rows = jdbcTemplate.query(
                """
                SELECT id, channel_type, value_raw, value_normalized, reason,
                       created_at, released_at, created_by, released_by
                FROM contact_suppressions
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> mapSuppression(rs),
                suppressionId, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public void releaseSuppression(UUID tenantId, UUID workspaceId, UUID suppressionId, UUID actorId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_suppressions
                SET released_at = NOW(), released_by = ?
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND released_at IS NULL
                """,
                actorId, suppressionId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Active suppression not found");
        }
    }

    public ConsentModels.SuppressionPage listSuppressions(
            UUID tenantId,
            UUID workspaceId,
            boolean activeOnly,
            int page,
            int size
    ) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        String activeClause = activeOnly ? " AND released_at IS NULL" : "";
        List<ConsentModels.SuppressionView> items = jdbcTemplate.query(
                """
                SELECT id, channel_type, value_raw, value_normalized, reason,
                       created_at, released_at, created_by, released_by
                FROM contact_suppressions
                WHERE tenant_id = ? AND workspace_id = ?
                """ + activeClause + """
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapSuppression(rs),
                tenantId, workspaceId, limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new ConsentModels.SuppressionPage(items, page, limit, hasNext);
    }

    public String consentStatusOrUnknown(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose
    ) {
        List<String> rows = jdbcTemplate.query(
                """
                SELECT status FROM contact_consents
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                  AND channel_type = ? AND purpose = ?
                """,
                (rs, i) -> rs.getString("status"),
                tenantId, workspaceId, contactId, channelType, purpose
        );
        return rows.isEmpty() ? "UNKNOWN" : rows.get(0);
    }

    private ConsentModels.ConsentView mapConsent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConsentModels.ConsentView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("workspace_id"),
                (UUID) rs.getObject("contact_id"),
                rs.getString("channel_type"),
                rs.getString("purpose"),
                rs.getString("status"),
                rs.getString("source"),
                parseJson(rs.getString("evidence")),
                rs.getTimestamp("captured_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                (UUID) rs.getObject("updated_by")
        );
    }

    private ConsentModels.SuppressionView mapSuppression(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp released = rs.getTimestamp("released_at");
        return new ConsentModels.SuppressionView(
                (UUID) rs.getObject("id"),
                rs.getString("channel_type"),
                rs.getString("value_raw"),
                rs.getString("value_normalized"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                released == null ? null : released.toInstant(),
                (UUID) rs.getObject("created_by"),
                (UUID) rs.getObject("released_by")
        );
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
