package com.rsreach.contacts;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContactRepository {

    private final JdbcTemplate jdbcTemplate;

    public ContactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insertContact(
            UUID tenantId,
            UUID workspaceId,
            String displayName,
            String primaryEmail,
            String primaryPhone,
            String customFieldsJson,
            UUID actorId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contacts (
                    id, tenant_id, home_workspace_id, display_name, status,
                    primary_email_normalized, primary_phone_e164, custom_fields,
                    created_by, updated_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, CAST(? AS jsonb), ?, ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, displayName, primaryEmail, primaryPhone, customFieldsJson, actorId, actorId
        );
        return id;
    }

    public void updateContact(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String displayName,
            String primaryEmail,
            String primaryPhone,
            String customFieldsJson,
            UUID actorId
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contacts
                SET display_name = ?,
                    primary_email_normalized = ?,
                    primary_phone_e164 = ?,
                    custom_fields = CAST(? AS jsonb),
                    updated_by = ?,
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ? AND status = 'ACTIVE'
                """,
                displayName, primaryEmail, primaryPhone, customFieldsJson, actorId, contactId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new com.rsreach.common.error.ApiException(
                    com.rsreach.common.error.ErrorCode.NOT_FOUND, "Contact not found");
        }
    }

    /** Merge may update an ARCHIVED survivor; does not require ACTIVE status. */
    public void updateContactForMerge(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String displayName,
            String primaryEmail,
            String primaryPhone,
            String customFieldsJson,
            UUID actorId
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contacts
                SET display_name = ?,
                    primary_email_normalized = ?,
                    primary_phone_e164 = ?,
                    custom_fields = CAST(? AS jsonb),
                    updated_by = COALESCE(?, updated_by),
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                  AND merged_into_contact_id IS NULL
                """,
                displayName, primaryEmail, primaryPhone, customFieldsJson, actorId,
                contactId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new com.rsreach.common.error.ApiException(
                    com.rsreach.common.error.ErrorCode.NOT_FOUND, "Contact not found");
        }
    }

    public void updateLinkedCustomerUser(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            UUID linkedCustomerUserId
    ) {
        jdbcTemplate.update(
                """
                UPDATE contacts
                SET linked_customer_user_id = ?, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                  AND merged_into_contact_id IS NULL
                """,
                linkedCustomerUserId, contactId, tenantId, workspaceId
        );
    }

    public boolean isMergeLoser(UUID tenantId, UUID workspaceId, UUID contactId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contacts
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                  AND merged_into_contact_id IS NOT NULL
                """,
                Integer.class, contactId, tenantId, workspaceId
        );
        return count != null && count > 0;
    }

    public void archive(UUID tenantId, UUID workspaceId, UUID contactId, UUID actorId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contacts
                SET status = 'ARCHIVED', archived_at = NOW(), updated_by = ?, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ? AND status = 'ACTIVE'
                """,
                actorId, contactId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new com.rsreach.common.error.ApiException(
                    com.rsreach.common.error.ErrorCode.NOT_FOUND, "Contact not found");
        }
        jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET status = 'ARCHIVED', updated_at = NOW()
                WHERE contact_id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'ACTIVE'
                """,
                contactId, tenantId, workspaceId
        );
    }

    public void restore(UUID tenantId, UUID workspaceId, UUID contactId, UUID actorId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE contacts
                SET status = 'ACTIVE', archived_at = NULL, updated_by = ?, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ? AND status = 'ARCHIVED'
                """,
                actorId, contactId, tenantId, workspaceId
        );
        if (updated == 0) {
            throw new com.rsreach.common.error.ApiException(
                    com.rsreach.common.error.ErrorCode.NOT_FOUND, "Contact not found");
        }
        jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET status = 'ACTIVE', updated_at = NOW()
                WHERE contact_id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'ARCHIVED'
                """,
                contactId, tenantId, workspaceId
        );
    }

    /**
     * Locks the home-workspace contact row for concurrent consent updates / seeding.
     * @return true when the contact exists in this tenant/home workspace
     */
    public boolean lockById(UUID tenantId, UUID workspaceId, UUID contactId) {
        List<UUID> rows = jdbcTemplate.query(
                """
                SELECT id FROM contacts
                WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                FOR UPDATE
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                contactId, tenantId, workspaceId
        );
        return !rows.isEmpty();
    }

    public boolean hasActiveChannelValue(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String valueNormalized
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM contact_channels
                WHERE tenant_id = ?
                  AND workspace_id = ?
                  AND contact_id = ?
                  AND channel_type = ?
                  AND value_normalized = ?
                  AND status = 'ACTIVE'
                """,
                Integer.class,
                tenantId, workspaceId, contactId, channelType, valueNormalized
        );
        return count != null && count > 0;
    }

    public Optional<ContactModels.ContactView> findByIdHomeOnly(UUID tenantId, UUID workspaceId, UUID contactId) {
        return loadById(tenantId, contactId, workspaceId, true);
    }

    public Optional<ContactModels.ContactView> findByIdAnyHome(UUID tenantId, UUID contactId) {
        return loadById(tenantId, contactId, null, false);
    }

    /**
     * Home workspace contact, or contact actively shared to {@code workspaceId}.
     */
    public Optional<ContactModels.ContactView> findById(UUID tenantId, UUID workspaceId, UUID contactId) {
        Optional<ContactModels.ContactView> home = findByIdHomeOnly(tenantId, workspaceId, contactId);
        if (home.isPresent()) {
            return home;
        }
        List<ContactModels.ContactView> rows = jdbcTemplate.query(
                """
                SELECT c.id, c.tenant_id, c.home_workspace_id, c.display_name, c.status,
                       c.primary_email_normalized, c.primary_phone_e164, c.custom_fields::text,
                       c.created_at, c.updated_at, c.archived_at
                FROM contacts c
                JOIN resource_shares s
                  ON s.tenant_id = c.tenant_id
                 AND s.resource_type = 'CONTACT'
                 AND s.resource_id = c.id
                 AND s.target_workspace_id = ?
                 AND s.revoked_at IS NULL
                WHERE c.id = ? AND c.tenant_id = ?
                """,
                (rs, i) -> mapContact(rs, List.of()),
                workspaceId, contactId, tenantId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ContactModels.ContactView base = rows.get(0);
        List<ContactModels.ChannelView> channels = listChannels(tenantId, base.homeWorkspaceId(), contactId);
        return Optional.of(withChannels(base, channels));
    }

    private Optional<ContactModels.ContactView> loadById(
            UUID tenantId, UUID contactId, UUID homeWorkspaceId, boolean requireHome
    ) {
        List<ContactModels.ContactView> rows;
        if (requireHome) {
            rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, home_workspace_id, display_name, status,
                           primary_email_normalized, primary_phone_e164, custom_fields::text,
                           created_at, updated_at, archived_at
                    FROM contacts
                    WHERE id = ? AND tenant_id = ? AND home_workspace_id = ?
                    """,
                    (rs, i) -> mapContact(rs, List.of()),
                    contactId, tenantId, homeWorkspaceId
            );
        } else {
            rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, home_workspace_id, display_name, status,
                           primary_email_normalized, primary_phone_e164, custom_fields::text,
                           created_at, updated_at, archived_at
                    FROM contacts
                    WHERE id = ? AND tenant_id = ?
                    """,
                    (rs, i) -> mapContact(rs, List.of()),
                    contactId, tenantId
            );
        }
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ContactModels.ContactView base = rows.get(0);
        List<ContactModels.ChannelView> channels = listChannels(tenantId, base.homeWorkspaceId(), contactId);
        return Optional.of(withChannels(base, channels));
    }

    private static ContactModels.ContactView withChannels(
            ContactModels.ContactView base, List<ContactModels.ChannelView> channels
    ) {
        return new ContactModels.ContactView(
                base.id(), base.tenantId(), base.homeWorkspaceId(), base.displayName(), base.status(),
                base.primaryEmail(), base.primaryPhone(), base.customFields(), channels,
                base.createdAt(), base.updatedAt(), base.archivedAt(), base.access()
        );
    }

    public ContactModels.PageResult search(
            UUID tenantId,
            UUID workspaceId,
            String query,
            String status,
            int page,
            int size
    ) {
        String statusFilter = status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase();
        String q = query == null ? "" : query.trim();
        String like = "%" + q.toLowerCase() + "%";
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;

        List<ContactModels.ContactView> items = jdbcTemplate.query(
                """
                SELECT id, tenant_id, home_workspace_id, display_name, status,
                       primary_email_normalized, primary_phone_e164, custom_fields::text,
                       created_at, updated_at, archived_at
                FROM (
                    SELECT c.id, c.tenant_id, c.home_workspace_id, c.display_name, c.status,
                           c.primary_email_normalized, c.primary_phone_e164, c.custom_fields::text,
                           c.created_at, c.updated_at, c.archived_at
                    FROM contacts c
                    WHERE c.tenant_id = ?
                      AND c.home_workspace_id = ?
                      AND c.status = ?
                      AND (
                        ? = ''
                        OR lower(c.display_name) LIKE ?
                        OR coalesce(c.primary_email_normalized, '') LIKE ?
                        OR coalesce(c.primary_phone_e164, '') LIKE ?
                      )
                    UNION
                    SELECT c.id, c.tenant_id, c.home_workspace_id, c.display_name, c.status,
                           c.primary_email_normalized, c.primary_phone_e164, c.custom_fields::text,
                           c.created_at, c.updated_at, c.archived_at
                    FROM contacts c
                    JOIN resource_shares s
                      ON s.tenant_id = c.tenant_id
                     AND s.resource_type = 'CONTACT'
                     AND s.resource_id = c.id
                     AND s.target_workspace_id = ?
                     AND s.revoked_at IS NULL
                    WHERE c.tenant_id = ?
                      AND c.home_workspace_id <> ?
                      AND c.status = ?
                      AND (
                        ? = ''
                        OR lower(c.display_name) LIKE ?
                        OR coalesce(c.primary_email_normalized, '') LIKE ?
                        OR coalesce(c.primary_phone_e164, '') LIKE ?
                      )
                ) accessible
                ORDER BY updated_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapContact(rs, List.of()),
                tenantId, workspaceId, statusFilter, q, like, like, like,
                workspaceId, tenantId, workspaceId, statusFilter, q, like, like, like,
                limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        List<ContactModels.ContactView> withChannels = new ArrayList<>();
        for (ContactModels.ContactView item : items) {
            withChannels.add(withChannels(
                    item, listChannels(tenantId, item.homeWorkspaceId(), item.id())));
        }
        return new ContactModels.PageResult(withChannels, page, limit, hasNext);
    }

    public void replaceChannels(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            List<PreparedChannel> channels
    ) {
        jdbcTemplate.update(
                "DELETE FROM contact_channels WHERE contact_id = ? AND tenant_id = ? AND workspace_id = ?",
                contactId, tenantId, workspaceId
        );
        for (PreparedChannel channel : channels) {
            jdbcTemplate.update(
                    """
                    INSERT INTO contact_channels (
                        id, tenant_id, workspace_id, contact_id, channel_type,
                        value_raw, value_normalized, is_primary, label, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                    """,
                    UUID.randomUUID(), tenantId, workspaceId, contactId,
                    channel.channelType(), channel.valueRaw(), channel.valueNormalized(),
                    channel.primary(), channel.label()
            );
        }
    }

    public Optional<UUID> findActiveContactIdByNormalized(
            UUID tenantId,
            UUID workspaceId,
            String channelType,
            String valueNormalized
    ) {
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT c.contact_id
                FROM contact_channels c
                JOIN contacts ct ON ct.id = c.contact_id
                WHERE c.tenant_id = ?
                  AND c.workspace_id = ?
                  AND c.channel_type = ?
                  AND c.value_normalized = ?
                  AND c.status = 'ACTIVE'
                  AND ct.status = 'ACTIVE'
                ORDER BY c.created_at
                LIMIT 1
                """,
                (rs, i) -> (UUID) rs.getObject("contact_id"),
                tenantId, workspaceId, channelType, valueNormalized
        );
        return ids.stream().findFirst();
    }

    public boolean activeChannelExists(
            UUID tenantId,
            UUID workspaceId,
            String channelType,
            String valueNormalized,
            UUID excludeContactId
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM contact_channels c
                JOIN contacts ct ON ct.id = c.contact_id
                WHERE c.tenant_id = ?
                  AND c.workspace_id = ?
                  AND c.channel_type = ?
                  AND c.value_normalized = ?
                  AND c.status = 'ACTIVE'
                  AND ct.status = 'ACTIVE'
                  AND (?::uuid IS NULL OR c.contact_id <> ?)
                """,
                Integer.class,
                tenantId, workspaceId, channelType, valueNormalized, excludeContactId, excludeContactId
        );
        return count != null && count > 0;
    }

    public List<ContactModels.ChannelView> listChannels(UUID tenantId, UUID workspaceId, UUID contactId) {
        return jdbcTemplate.query(
                """
                SELECT id, channel_type, value_raw, value_normalized, is_primary, label, status
                FROM contact_channels
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ?
                ORDER BY channel_type, is_primary DESC, created_at
                """,
                (rs, i) -> new ContactModels.ChannelView(
                        (UUID) rs.getObject("id"),
                        rs.getString("channel_type"),
                        rs.getString("value_raw"),
                        rs.getString("value_normalized"),
                        rs.getBoolean("is_primary"),
                        rs.getString("label"),
                        rs.getString("status")
                ),
                tenantId, workspaceId, contactId
        );
    }

    public UUID insertFieldDef(
            UUID tenantId,
            UUID workspaceId,
            ContactModels.CustomFieldDefCreateCommand command
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_custom_field_defs (
                    id, tenant_id, workspace_id, field_key, label, data_type, required, active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, NOW(), NOW())
                """,
                id, tenantId, workspaceId, command.fieldKey(), command.label(),
                command.dataType().toUpperCase(), command.required()
        );
        return id;
    }

    public List<ContactModels.CustomFieldDef> listFieldDefs(UUID tenantId, UUID workspaceId, boolean activeOnly) {
        String sql = """
                SELECT id, tenant_id, workspace_id, field_key, label, data_type, required, active
                FROM contact_custom_field_defs
                WHERE tenant_id = ? AND workspace_id = ?
                """ + (activeOnly ? " AND active = TRUE" : "") + " ORDER BY field_key";
        return jdbcTemplate.query(
                sql,
                (rs, i) -> new ContactModels.CustomFieldDef(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("workspace_id"),
                        rs.getString("field_key"),
                        rs.getString("label"),
                        rs.getString("data_type"),
                        rs.getBoolean("required"),
                        rs.getBoolean("active")
                ),
                tenantId, workspaceId
        );
    }

    private ContactModels.ContactView mapContact(
            java.sql.ResultSet rs,
            List<ContactModels.ChannelView> channels
    ) throws java.sql.SQLException {
        Timestamp archived = rs.getTimestamp("archived_at");
        return new ContactModels.ContactView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("home_workspace_id"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getString("primary_email_normalized"),
                rs.getString("primary_phone_e164"),
                CustomFieldValidator.fromJson(rs.getString("custom_fields")),
                channels,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                archived == null ? null : archived.toInstant(),
                null
        );
    }

    public record PreparedChannel(
            String channelType,
            String valueRaw,
            String valueNormalized,
            boolean primary,
            String label
    ) {
    }
}
