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
public class ContactGroupRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public ContactGroupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insert(
            UUID tenantId,
            UUID workspaceId,
            String name,
            String slug,
            String groupType,
            String filterJson,
            UUID actorId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_groups (
                    id, tenant_id, workspace_id, name, slug, group_type, filter_definition,
                    status, materialization_status, created_by, updated_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'ACTIVE',
                          CASE WHEN ? = 'DYNAMIC' THEN 'PENDING' ELSE 'IDLE' END,
                          ?, ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, name, slug, groupType, filterJson, groupType, actorId, actorId
        );
        return id;
    }

    public void update(
            UUID tenantId,
            UUID workspaceId,
            UUID groupId,
            String name,
            String filterJson,
            boolean dynamicPending,
            UUID actorId
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_groups
                SET name = ?,
                    filter_definition = CAST(? AS jsonb),
                    materialization_status = CASE WHEN ? THEN 'PENDING' ELSE materialization_status END,
                    materialization_error = CASE WHEN ? THEN NULL ELSE materialization_error END,
                    updated_by = ?,
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'ACTIVE'
                """,
                name, filterJson, dynamicPending, dynamicPending, actorId, groupId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Contact group not found");
        }
    }

    public void archive(UUID tenantId, UUID workspaceId, UUID groupId, UUID actorId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_groups
                SET status = 'ARCHIVED', archived_at = NOW(), updated_by = ?, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'ACTIVE'
                """,
                actorId, groupId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Contact group not found");
        }
    }

    public Optional<ContactGroupModels.GroupView> findByIdHomeOnly(UUID tenantId, UUID workspaceId, UUID groupId) {
        List<ContactGroupModels.GroupView> home = jdbcTemplate.query(
                """
                SELECT g.*,
                       (SELECT COUNT(*) FROM contact_group_members m
                        WHERE m.group_id = g.id AND m.tenant_id = g.tenant_id AND m.workspace_id = g.workspace_id) AS member_count
                FROM contact_groups g
                WHERE g.id = ? AND g.tenant_id = ? AND g.workspace_id = ?
                """,
                (rs, i) -> mapGroup(rs),
                groupId, tenantId, workspaceId
        );
        return home.stream().findFirst();
    }

    /**
     * Home workspace group, or group actively shared to {@code workspaceId}.
     */
    public Optional<ContactGroupModels.GroupView> findById(UUID tenantId, UUID workspaceId, UUID groupId) {
        Optional<ContactGroupModels.GroupView> home = findByIdHomeOnly(tenantId, workspaceId, groupId);
        if (home.isPresent()) {
            return home;
        }
        List<ContactGroupModels.GroupView> shared = jdbcTemplate.query(
                """
                SELECT g.*,
                       (SELECT COUNT(*) FROM contact_group_members m
                        WHERE m.group_id = g.id AND m.tenant_id = g.tenant_id AND m.workspace_id = g.workspace_id) AS member_count
                FROM contact_groups g
                JOIN resource_shares s
                  ON s.tenant_id = g.tenant_id
                 AND s.resource_type = 'CONTACT_GROUP'
                 AND s.resource_id = g.id
                 AND s.target_workspace_id = ?
                 AND s.revoked_at IS NULL
                WHERE g.id = ? AND g.tenant_id = ?
                """,
                (rs, i) -> mapGroup(rs),
                workspaceId, groupId, tenantId
        );
        return shared.stream().findFirst();
    }

    public Optional<ContactGroupModels.GroupView> lockById(UUID tenantId, UUID workspaceId, UUID groupId) {
        List<ContactGroupModels.GroupView> rows = jdbcTemplate.query(
                """
                SELECT g.*,
                       (SELECT COUNT(*) FROM contact_group_members m
                        WHERE m.group_id = g.id AND m.tenant_id = g.tenant_id AND m.workspace_id = g.workspace_id) AS member_count
                FROM contact_groups g
                WHERE g.id = ? AND g.tenant_id = ? AND g.workspace_id = ?
                FOR UPDATE
                """,
                (rs, i) -> mapGroup(rs),
                groupId, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public ContactGroupModels.GroupPage search(
            UUID tenantId,
            UUID workspaceId,
            String query,
            String status,
            String groupType,
            int page,
            int size
    ) {
        String statusFilter = status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase();
        String q = query == null ? "" : query.trim();
        String like = "%" + q.toLowerCase() + "%";
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        boolean filterType = groupType != null && !groupType.isBlank();
        String type = filterType ? groupType.trim().toUpperCase() : null;

        List<ContactGroupModels.GroupView> items = jdbcTemplate.query(
                """
                SELECT g.*,
                       (SELECT COUNT(*) FROM contact_group_members m
                        WHERE m.group_id = g.id AND m.tenant_id = g.tenant_id AND m.workspace_id = g.workspace_id) AS member_count
                FROM (
                    SELECT g.id, g.tenant_id, g.workspace_id, g.name, g.slug, g.group_type, g.status,
                           g.filter_definition, g.materialization_status, g.materialization_error, g.materialized_at,
                           g.created_by, g.created_at, g.updated_at, g.archived_at
                    FROM contact_groups g
                    WHERE g.tenant_id = ?
                      AND g.workspace_id = ?
                      AND g.status = ?
                      AND (? = FALSE OR g.group_type = ?)
                      AND (
                        ? = ''
                        OR lower(g.name) LIKE ?
                        OR lower(g.slug) LIKE ?
                      )
                    UNION
                    SELECT g.id, g.tenant_id, g.workspace_id, g.name, g.slug, g.group_type, g.status,
                           g.filter_definition, g.materialization_status, g.materialization_error, g.materialized_at,
                           g.created_by, g.created_at, g.updated_at, g.archived_at
                    FROM contact_groups g
                    JOIN resource_shares s
                      ON s.tenant_id = g.tenant_id
                     AND s.resource_type = 'CONTACT_GROUP'
                     AND s.resource_id = g.id
                     AND s.target_workspace_id = ?
                     AND s.revoked_at IS NULL
                    WHERE g.tenant_id = ?
                      AND g.workspace_id <> ?
                      AND g.status = ?
                      AND (? = FALSE OR g.group_type = ?)
                      AND (
                        ? = ''
                        OR lower(g.name) LIKE ?
                        OR lower(g.slug) LIKE ?
                      )
                ) g
                ORDER BY g.updated_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapGroup(rs),
                tenantId, workspaceId, statusFilter, filterType, type, q, like, like,
                workspaceId, tenantId, workspaceId, statusFilter, filterType, type, q, like, like,
                limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new ContactGroupModels.GroupPage(items, page, limit, hasNext);
    }

    public boolean slugExists(UUID tenantId, UUID workspaceId, String slug, UUID excludeId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_groups
                WHERE tenant_id = ? AND workspace_id = ? AND slug = ?
                  AND (?::uuid IS NULL OR id <> ?)
                """,
                Integer.class, tenantId, workspaceId, slug, excludeId, excludeId
        );
        return count != null && count > 0;
    }

    public int countValidContacts(UUID tenantId, UUID workspaceId, List<UUID> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", contactIds.stream().map(id -> "?").toList());
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(workspaceId);
        params.addAll(contactIds);
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contacts
                WHERE tenant_id = ? AND home_workspace_id = ?
                  AND status = 'ACTIVE'
                  AND id IN (""" + placeholders + ")",
                Integer.class,
                params.toArray()
        );
        return count == null ? 0 : count;
    }

    public int addManualMembers(UUID tenantId, UUID workspaceId, UUID groupId, List<UUID> contactIds) {
        int added = 0;
        for (UUID contactId : contactIds) {
            int rows = jdbcTemplate.update(
                    """
                    INSERT INTO contact_group_members (
                        id, tenant_id, workspace_id, group_id, contact_id, source, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'MANUAL', NOW())
                    ON CONFLICT (tenant_id, workspace_id, group_id, contact_id) DO NOTHING
                    """,
                    UUID.randomUUID(), tenantId, workspaceId, groupId, contactId
            );
            added += rows;
        }
        return added;
    }

    public int removeMembers(UUID tenantId, UUID workspaceId, UUID groupId, List<UUID> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", contactIds.stream().map(id -> "?").toList());
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(workspaceId);
        params.add(groupId);
        params.addAll(contactIds);
        Integer removed = jdbcTemplate.update(
                "DELETE FROM contact_group_members WHERE tenant_id = ? AND workspace_id = ? AND group_id = ?"
                        + " AND contact_id IN (" + placeholders + ")",
                params.toArray()
        );
        return removed;
    }

    public ContactGroupModels.MemberPage listMembers(
            UUID tenantId,
            UUID workspaceId,
            UUID groupId,
            int page,
            int size
    ) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        Long total = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_group_members
                WHERE tenant_id = ? AND workspace_id = ? AND group_id = ?
                """,
                Long.class, tenantId, workspaceId, groupId
        );
        List<ContactGroupModels.MemberView> items = jdbcTemplate.query(
                """
                SELECT m.contact_id, c.display_name, c.status, m.source,
                       c.primary_email_normalized, c.primary_phone_e164, m.created_at
                FROM contact_group_members m
                JOIN contacts c ON c.id = m.contact_id
                WHERE m.tenant_id = ? AND m.workspace_id = ? AND m.group_id = ?
                ORDER BY m.created_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> new ContactGroupModels.MemberView(
                        (UUID) rs.getObject("contact_id"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("source"),
                        rs.getString("primary_email_normalized"),
                        rs.getString("primary_phone_e164"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                tenantId, workspaceId, groupId, limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new ContactGroupModels.MemberPage(items, page, limit, hasNext, total == null ? 0L : total);
    }

    public long countMatching(
            UUID tenantId,
            UUID workspaceId,
            GroupFilterQueryBuilder.BuiltFilter filter
    ) {
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(workspaceId);
        params.addAll(filter.params());
        String sql = """
                SELECT COUNT(*)
                FROM contacts c
                WHERE c.tenant_id = ?
                  AND c.home_workspace_id = ?
                  AND (
                """ + filter.whereSql() + ")";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return count == null ? 0L : count;
    }

    public List<UUID> listMatchingContactIds(
            UUID tenantId,
            UUID workspaceId,
            GroupFilterQueryBuilder.BuiltFilter filter,
            int limit,
            int offset
    ) {
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(workspaceId);
        params.addAll(filter.params());
        params.add(limit);
        params.add(offset);
        String sql = """
                SELECT c.id
                FROM contacts c
                WHERE c.tenant_id = ?
                  AND c.home_workspace_id = ?
                  AND (
                """ + filter.whereSql() + """
                )
                ORDER BY c.id
                LIMIT ? OFFSET ?
                """;
        return jdbcTemplate.query(sql, (rs, i) -> (UUID) rs.getObject("id"), params.toArray());
    }

    public List<ContactModels.ContactView> listMatchingContacts(
            UUID tenantId,
            UUID workspaceId,
            GroupFilterQueryBuilder.BuiltFilter filter,
            int limit,
            int offset
    ) {
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(workspaceId);
        params.addAll(filter.params());
        params.add(limit);
        params.add(offset);
        String sql = """
                SELECT c.id, c.tenant_id, c.home_workspace_id, c.display_name, c.status,
                       c.primary_email_normalized, c.primary_phone_e164, c.custom_fields::text,
                       c.created_at, c.updated_at, c.archived_at
                FROM contacts c
                WHERE c.tenant_id = ?
                  AND c.home_workspace_id = ?
                  AND (
                """ + filter.whereSql() + """
                )
                ORDER BY c.updated_at DESC
                LIMIT ? OFFSET ?
                """;
        return jdbcTemplate.query(
                sql,
                (rs, i) -> new ContactModels.ContactView(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("home_workspace_id"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("primary_email_normalized"),
                        rs.getString("primary_phone_e164"),
                        CustomFieldValidator.fromJson(rs.getString("custom_fields")),
                        List.of(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("archived_at") == null ? null : rs.getTimestamp("archived_at").toInstant(),
                        null
                ),
                params.toArray()
        );
    }

    public void deleteDynamicMembers(UUID tenantId, UUID workspaceId, UUID groupId) {
        jdbcTemplate.update(
                """
                DELETE FROM contact_group_members
                WHERE tenant_id = ? AND workspace_id = ? AND group_id = ? AND source = 'DYNAMIC'
                """,
                tenantId, workspaceId, groupId
        );
    }

    public void insertDynamicMembers(UUID tenantId, UUID workspaceId, UUID groupId, List<UUID> contactIds) {
        for (UUID contactId : contactIds) {
            jdbcTemplate.update(
                    """
                    INSERT INTO contact_group_members (
                        id, tenant_id, workspace_id, group_id, contact_id, source, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'DYNAMIC', NOW())
                    ON CONFLICT (tenant_id, workspace_id, group_id, contact_id)
                    DO UPDATE SET source = CASE
                        WHEN contact_group_members.source = 'MANUAL' THEN 'MANUAL'
                        ELSE 'DYNAMIC'
                    END
                    """,
                    UUID.randomUUID(), tenantId, workspaceId, groupId, contactId
            );
        }
    }

    public void markMaterializationRunning(UUID tenantId, UUID workspaceId, UUID groupId) {
        jdbcTemplate.update(
                """
                UPDATE contact_groups
                SET materialization_status = 'RUNNING', materialization_error = NULL, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                groupId, tenantId, workspaceId
        );
    }

    public void markMaterializationSuccess(UUID tenantId, UUID workspaceId, UUID groupId) {
        jdbcTemplate.update(
                """
                UPDATE contact_groups
                SET materialization_status = 'IDLE',
                    materialized_at = NOW(),
                    materialization_error = NULL,
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                groupId, tenantId, workspaceId
        );
    }

    public void markMaterializationFailed(UUID tenantId, UUID workspaceId, UUID groupId, String error) {
        jdbcTemplate.update(
                """
                UPDATE contact_groups
                SET materialization_status = 'FAILED',
                    materialization_error = ?,
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                error == null ? null : error.substring(0, Math.min(error.length(), 500)),
                groupId, tenantId, workspaceId
        );
    }

    public void markMaterializationPending(UUID tenantId, UUID workspaceId, UUID groupId) {
        jdbcTemplate.update(
                """
                UPDATE contact_groups
                SET materialization_status = 'PENDING', materialization_error = NULL, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'ACTIVE'
                """,
                groupId, tenantId, workspaceId
        );
    }

    public List<UUID> listStaleDynamicGroupIds(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id FROM contact_groups
                WHERE group_type = 'DYNAMIC'
                  AND status = 'ACTIVE'
                  AND materialization_status IN ('IDLE', 'FAILED')
                  AND (
                    materialized_at IS NULL
                    OR materialized_at < NOW() - INTERVAL '15 minutes'
                  )
                ORDER BY COALESCE(materialized_at, to_timestamp(0)) ASC
                LIMIT ?
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                limit
        );
    }

    public Optional<Scope> findGroupScope(UUID groupId) {
        List<Scope> rows = jdbcTemplate.query(
                "SELECT tenant_id, workspace_id FROM contact_groups WHERE id = ?",
                (rs, i) -> new Scope((UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("workspace_id")),
                groupId
        );
        return rows.stream().findFirst();
    }

    private ContactGroupModels.GroupView mapGroup(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp archived = rs.getTimestamp("archived_at");
        Timestamp materialized = rs.getTimestamp("materialized_at");
        String filterJson = rs.getString("filter_definition");
        Map<String, Object> filter = Map.of();
        if (filterJson != null && !filterJson.isBlank()) {
            try {
                filter = MAPPER.readValue(filterJson, new TypeReference<>() {
                });
            } catch (Exception ignored) {
                filter = Map.of();
            }
        }
        return new ContactGroupModels.GroupView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("workspace_id"),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("group_type"),
                filter,
                rs.getString("status"),
                rs.getString("materialization_status"),
                rs.getString("materialization_error"),
                materialized == null ? null : materialized.toInstant(),
                rs.getLong("member_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                archived == null ? null : archived.toInstant()
        );
    }

    public record Scope(UUID tenantId, UUID workspaceId) {
    }
}
