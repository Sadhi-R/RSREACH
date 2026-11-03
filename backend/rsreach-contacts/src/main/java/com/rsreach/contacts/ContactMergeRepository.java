package com.rsreach.contacts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContactMergeRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public ContactMergeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UUID> findLinkedCustomerUserId(UUID tenantId, UUID workspaceId, UUID contactId) {
        List<UUID> rows = jdbcTemplate.query(
                """
                SELECT linked_customer_user_id FROM contacts
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                """,
                (rs, i) -> (UUID) rs.getObject("linked_customer_user_id"),
                contactId, tenantId, workspaceId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rows.get(0));
    }

    public boolean contactExists(UUID tenantId, UUID workspaceId, UUID contactId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contacts
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                """,
                Integer.class, contactId, tenantId, workspaceId
        );
        return count != null && count > 0;
    }

    public Optional<UUID> findMergedInto(UUID tenantId, UUID workspaceId, UUID contactId) {
        List<UUID> rows = jdbcTemplate.query(
                """
                SELECT merged_into_contact_id FROM contacts
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                """,
                (rs, i) -> (UUID) rs.getObject("merged_into_contact_id"),
                contactId, tenantId, workspaceId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rows.get(0));
    }

    public boolean isMergeLoser(UUID tenantId, UUID workspaceId, UUID contactId) {
        return findMergedInto(tenantId, workspaceId, contactId).isPresent();
    }

    public void moveChannelToSurvivor(
            UUID tenantId,
            UUID workspaceId,
            UUID channelId,
            UUID fromContactId,
            UUID toContactId
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET contact_id = ?, is_primary = FALSE, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                  AND contact_id = ? AND status = 'ACTIVE'
                """,
                toContactId, channelId, tenantId, workspaceId, fromContactId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Channel move failed");
        }
    }

    public void archiveChannel(UUID tenantId, UUID workspaceId, UUID channelId, UUID contactId) {
        jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET status = 'ARCHIVED', is_primary = FALSE, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND contact_id = ?
                """,
                channelId, tenantId, workspaceId, contactId
        );
    }

    public void clearPrimaries(UUID tenantId, UUID workspaceId, UUID contactId, String channelType) {
        jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET is_primary = FALSE, updated_at = NOW()
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                  AND channel_type = ? AND status = 'ACTIVE'
                """,
                tenantId, workspaceId, contactId, channelType
        );
    }

    public void setPrimary(UUID tenantId, UUID workspaceId, UUID contactId, UUID channelId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET is_primary = TRUE, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                  AND contact_id = ? AND status = 'ACTIVE'
                """,
                channelId, tenantId, workspaceId, contactId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Primary channel update failed");
        }
    }

    public void markLoserMerged(
            UUID tenantId,
            UUID workspaceId,
            UUID loserId,
            UUID survivorId,
            UUID actorId
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contacts
                SET status = 'ARCHIVED',
                    archived_at = COALESCE(archived_at, NOW()),
                    merged_into_contact_id = ?,
                    merged_at = NOW(),
                    updated_by = ?,
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                  AND merged_into_contact_id IS NULL
                """,
                survivorId, actorId, loserId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Contact cannot be marked as merge loser");
        }
        jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET status = CASE WHEN status = 'ACTIVE' THEN 'ARCHIVED' ELSE status END,
                    is_primary = FALSE,
                    updated_at = NOW()
                WHERE contact_id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'ACTIVE'
                """,
                loserId, tenantId, workspaceId
        );
    }

    public List<GroupMembership> listMemberships(UUID tenantId, UUID workspaceId, UUID contactId) {
        return jdbcTemplate.query(
                """
                SELECT m.group_id, g.group_type, m.source
                FROM contact_group_members m
                JOIN contact_groups g ON g.id = m.group_id AND g.tenant_id = m.tenant_id
                WHERE m.tenant_id = ? AND m.workspace_id = ? AND m.contact_id = ?
                ORDER BY g.group_type, m.group_id
                """,
                (rs, i) -> new GroupMembership(
                        (UUID) rs.getObject("group_id"),
                        rs.getString("group_type"),
                        rs.getString("source")
                ),
                tenantId, workspaceId, contactId
        );
    }

    public List<UUID> listDynamicGroupIds(UUID tenantId, UUID workspaceId) {
        return jdbcTemplate.query(
                """
                SELECT id FROM contact_groups
                WHERE tenant_id = ? AND workspace_id = ?
                  AND group_type = 'DYNAMIC' AND status = 'ACTIVE'
                ORDER BY id
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId
        );
    }

    public List<ShareRow> listActiveContactShares(UUID tenantId, UUID contactId) {
        return jdbcTemplate.query(
                """
                SELECT id, source_workspace_id, target_workspace_id, permission
                FROM resource_shares
                WHERE tenant_id = ?
                  AND resource_type = 'CONTACT'
                  AND resource_id = ?
                  AND revoked_at IS NULL
                ORDER BY id
                """,
                (rs, i) -> new ShareRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("source_workspace_id"),
                        (UUID) rs.getObject("target_workspace_id"),
                        rs.getString("permission")
                ),
                tenantId, contactId
        );
    }

    public int revokeShare(UUID tenantId, UUID shareId) {
        return jdbcTemplate.update(
                """
                UPDATE resource_shares
                SET revoked_at = NOW()
                WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL
                """,
                shareId, tenantId
        );
    }

    public Optional<MergeModels.MergeView> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        List<MergeModels.MergeView> rows = jdbcTemplate.query(
                """
                SELECT id, workspace_id, survivor_contact_id, loser_contact_id, status,
                       idempotency_key, request_hash, preview_hash, actor_id, completed_at
                FROM contact_merges
                WHERE tenant_id = ? AND idempotency_key = ?
                """,
                (rs, i) -> mapMerge(rs, List.of()),
                tenantId, idempotencyKey
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        MergeModels.MergeView base = rows.get(0);
        return Optional.of(new MergeModels.MergeView(
                base.id(), base.workspaceId(), base.survivorContactId(), base.loserContactId(),
                base.status(), base.idempotencyKey(), base.requestHash(), base.previewHash(),
                base.actorId(), base.completedAt(), listItems(base.id())
        ));
    }

    public Optional<MergeModels.MergeView> findById(UUID tenantId, UUID workspaceId, UUID mergeId) {
        List<MergeModels.MergeView> rows = jdbcTemplate.query(
                """
                SELECT id, workspace_id, survivor_contact_id, loser_contact_id, status,
                       idempotency_key, request_hash, preview_hash, actor_id, completed_at
                FROM contact_merges
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> mapMerge(rs, List.of()),
                mergeId, tenantId, workspaceId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        MergeModels.MergeView base = rows.get(0);
        return Optional.of(new MergeModels.MergeView(
                base.id(), base.workspaceId(), base.survivorContactId(), base.loserContactId(),
                base.status(), base.idempotencyKey(), base.requestHash(), base.previewHash(),
                base.actorId(), base.completedAt(), listItems(base.id())
        ));
    }

    public MergeModels.MergePage list(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            int page,
            int size
    ) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(workspaceId);
        String contactClause = "";
        if (contactId != null) {
            contactClause = " AND (survivor_contact_id = ? OR loser_contact_id = ?)";
            params.add(contactId);
            params.add(contactId);
        }
        params.add(limit + 1);
        params.add(offset);
        List<MergeModels.MergeView> items = jdbcTemplate.query(
                """
                SELECT id, workspace_id, survivor_contact_id, loser_contact_id, status,
                       idempotency_key, request_hash, preview_hash, actor_id, completed_at
                FROM contact_merges
                WHERE tenant_id = ? AND workspace_id = ?
                """ + contactClause + """
                 ORDER BY completed_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapMerge(rs, List.of()),
                params.toArray()
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        List<MergeModels.MergeView> withItems = new ArrayList<>();
        for (MergeModels.MergeView item : items) {
            withItems.add(new MergeModels.MergeView(
                    item.id(), item.workspaceId(), item.survivorContactId(), item.loserContactId(),
                    item.status(), item.idempotencyKey(), item.requestHash(), item.previewHash(),
                    item.actorId(), item.completedAt(), listItems(item.id())
            ));
        }
        return new MergeModels.MergePage(withItems, page, limit, hasNext);
    }

    public UUID insertMerge(
            UUID tenantId,
            UUID workspaceId,
            UUID survivorId,
            UUID loserId,
            String idempotencyKey,
            String requestHash,
            String previewHash,
            UUID actorId,
            String requestSnapshotJson,
            String resultSnapshotJson
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_merges (
                    id, tenant_id, workspace_id, survivor_contact_id, loser_contact_id,
                    status, idempotency_key, request_hash, preview_hash, actor_id,
                    request_snapshot, result_snapshot, completed_at
                ) VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), NOW())
                """,
                id, tenantId, workspaceId, survivorId, loserId,
                idempotencyKey, requestHash, previewHash, actorId,
                requestSnapshotJson, resultSnapshotJson
        );
        return id;
    }

    public void insertItem(
            UUID mergeId,
            String itemType,
            String itemKey,
            String action,
            String beforeJson,
            String afterJson
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO contact_merge_items (id, merge_id, item_type, item_key, action, before_json, after_json)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                """,
                UUID.randomUUID(), mergeId, itemType, itemKey, action, beforeJson, afterJson
        );
    }

    public void markCandidateMerged(UUID tenantId, UUID workspaceId, UUID candidateId) {
        jdbcTemplate.update(
                """
                UPDATE contact_duplicate_candidates
                SET status = 'MERGED', updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                  AND status IN ('OPEN', 'DISMISSED')
                """,
                candidateId, tenantId, workspaceId
        );
    }

    public void supersedeCandidatesInvolving(UUID tenantId, UUID workspaceId, UUID contactId) {
        jdbcTemplate.update(
                """
                UPDATE contact_duplicate_candidates
                SET status = 'SUPERSEDED', updated_at = NOW()
                WHERE tenant_id = ? AND workspace_id = ?
                  AND status = 'OPEN'
                  AND (contact_id_a = ? OR contact_id_b = ?)
                """,
                tenantId, workspaceId, contactId, contactId
        );
    }

    public List<MergeModels.MergeItemView> listItems(UUID mergeId) {
        return jdbcTemplate.query(
                """
                SELECT id, item_type, item_key, action, before_json::text, after_json::text
                FROM contact_merge_items
                WHERE merge_id = ?
                ORDER BY item_type, item_key
                """,
                (rs, i) -> new MergeModels.MergeItemView(
                        (UUID) rs.getObject("id"),
                        rs.getString("item_type"),
                        rs.getString("item_key"),
                        rs.getString("action"),
                        parseJson(rs.getString("before_json")),
                        parseJson(rs.getString("after_json"))
                ),
                mergeId
        );
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JSON serialization failed");
        }
    }

    private MergeModels.MergeView mapMerge(java.sql.ResultSet rs, List<MergeModels.MergeItemView> items)
            throws java.sql.SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new MergeModels.MergeView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("workspace_id"),
                (UUID) rs.getObject("survivor_contact_id"),
                (UUID) rs.getObject("loser_contact_id"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("preview_hash"),
                (UUID) rs.getObject("actor_id"),
                completed == null ? null : completed.toInstant(),
                items
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

    public record GroupMembership(UUID groupId, String groupType, String source) {
    }

    public record ShareRow(UUID id, UUID sourceWorkspaceId, UUID targetWorkspaceId, String permission) {
    }
}
