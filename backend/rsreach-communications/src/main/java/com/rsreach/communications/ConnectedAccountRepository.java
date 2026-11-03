package com.rsreach.communications;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectedAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConnectedAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String provider,
            String ownershipMode,
            String displayName,
            String externalAccountRef,
            String secretName,
            String metadataJson,
            UUID createdBy
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO connected_accounts (
                    id, tenant_id, workspace_id, provider, ownership_mode, display_name,
                    external_account_ref, status, secret_name, metadata_json, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, CAST(? AS jsonb), ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, provider, ownershipMode, displayName,
                externalAccountRef, secretName, metadataJson == null ? "{}" : metadataJson, createdBy
        );
    }

    public Optional<CommunicationModels.ConnectedAccountView> findById(
            UUID tenantId, UUID workspaceId, UUID id
    ) {
        List<CommunicationModels.ConnectedAccountView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, provider, ownership_mode, display_name,
                       external_account_ref, status, created_at, disconnected_at
                FROM connected_accounts
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> map(rs),
                id, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public List<CommunicationModels.ConnectedAccountView> list(UUID tenantId, UUID workspaceId) {
        return jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, provider, ownership_mode, display_name,
                       external_account_ref, status, created_at, disconnected_at
                FROM connected_accounts
                WHERE tenant_id = ? AND workspace_id = ? AND disconnected_at IS NULL
                ORDER BY created_at DESC
                """,
                (rs, i) -> map(rs),
                tenantId, workspaceId
        );
    }

    /**
     * Active accounts for a provider account id. Limited to three rows so callers can tell
     * one match from an ambiguous set without scanning an unbounded list.
     */
    public List<CommunicationModels.ConnectedAccountView> findActiveByProviderAndExternalRef(
            String provider,
            String externalAccountRef
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, provider, ownership_mode, display_name,
                       external_account_ref, status, created_at, disconnected_at
                FROM connected_accounts
                WHERE provider = ? AND external_account_ref = ?
                  AND status = 'ACTIVE' AND disconnected_at IS NULL
                LIMIT 3
                """,
                (rs, i) -> map(rs),
                provider, externalAccountRef
        );
    }

    public long countInactiveByProviderAndExternalRef(String provider, String externalAccountRef) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM connected_accounts
                WHERE provider = ? AND external_account_ref = ?
                  AND (status <> 'ACTIVE' OR disconnected_at IS NOT NULL)
                """,
                Long.class,
                provider, externalAccountRef
        );
        return count == null ? 0L : count;
    }

    public Optional<String> findSecretName(UUID tenantId, UUID workspaceId, UUID id) {
        List<String> rows = jdbcTemplate.query(
                """
                SELECT secret_name FROM connected_accounts
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND disconnected_at IS NULL
                """,
                (rs, i) -> rs.getString("secret_name"),
                id, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public int disconnect(UUID tenantId, UUID workspaceId, UUID id) {
        return jdbcTemplate.update(
                """
                UPDATE connected_accounts
                SET status = 'DISCONNECTED', disconnected_at = NOW(), updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND disconnected_at IS NULL
                """,
                id, tenantId, workspaceId
        );
    }

    private static CommunicationModels.ConnectedAccountView map(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Timestamp disconnected = rs.getTimestamp("disconnected_at");
        return new CommunicationModels.ConnectedAccountView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("workspace_id"),
                rs.getString("provider"),
                rs.getString("ownership_mode"),
                rs.getString("display_name"),
                rs.getString("external_account_ref"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                disconnected == null ? null : disconnected.toInstant()
        );
    }
}
