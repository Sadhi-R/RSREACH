package com.rsreach.persistence.webhook;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable webhook ingestion with provider+account scoped uniqueness.
 */
@Repository
public class ProviderWebhookRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProviderWebhookRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts a webhook event. Duplicate (provider, connected_account_ref, provider_event_id)
     * returns the existing id with inserted=false and does not reset processing state.
     * Uses ON CONFLICT so PostgreSQL does not abort the transaction (catching unique
     * violations mid-transaction leaves the TX unusable).
     */
    @Transactional
    public WebhookIngestResult ingest(WebhookIngestCommand command) {
        UUID id = UUID.randomUUID();
        List<UUID> inserted = jdbcTemplate.query(
                """
                INSERT INTO provider_webhook_events (
                    id, provider, connected_account_ref, provider_event_id, event_type, event_time,
                    tenant_id, workspace_id, signature_valid, payload_json, payload_hash,
                    processing_status, attempt_count, available_at, received_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'RECEIVED', 0, NOW(), NOW(), NOW(), NOW())
                ON CONFLICT (provider, connected_account_ref, provider_event_id) DO NOTHING
                RETURNING id
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                id,
                command.provider(),
                command.connectedAccountRef(),
                command.providerEventId(),
                command.eventType(),
                command.eventTime() == null ? null : Timestamp.from(command.eventTime()),
                command.tenantId(),
                command.workspaceId(),
                command.signatureValid(),
                command.payloadJson(),
                command.payloadHash()
        );
        if (!inserted.isEmpty()) {
            return new WebhookIngestResult(inserted.get(0), true);
        }
        UUID existing = findId(command.provider(), command.connectedAccountRef(), command.providerEventId())
                .orElseThrow(() -> new IllegalStateException("Webhook conflict without existing row"));
        return new WebhookIngestResult(existing, false);
    }

    public Optional<UUID> findId(String provider, String connectedAccountRef, String providerEventId) {
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT id
                FROM provider_webhook_events
                WHERE provider = ? AND connected_account_ref = ? AND provider_event_id = ?
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                provider,
                connectedAccountRef,
                providerEventId
        );
        return ids.stream().findFirst();
    }

    @Transactional
    public void markQueued(UUID id) {
        jdbcTemplate.update(
                """
                UPDATE provider_webhook_events
                SET processing_status = 'QUEUED', updated_at = NOW()
                WHERE id = ? AND processing_status = 'RECEIVED'
                """,
                id
        );
    }

    @Transactional
    public void markProcessed(UUID id) {
        jdbcTemplate.update(
                """
                UPDATE provider_webhook_events
                SET processing_status = 'PROCESSED',
                    processed_at = NOW(),
                    locked_by = NULL,
                    locked_until = NULL,
                    last_error = NULL,
                    updated_at = NOW()
                WHERE id = ?
                """,
                id
        );
    }

    @Transactional
    public void markRetryOrDead(UUID id, String error, Instant nextAvailableAt, int maxAttempts) {
        jdbcTemplate.update(
                """
                UPDATE provider_webhook_events
                SET processing_status = CASE
                        WHEN attempt_count >= ? THEN 'DEAD'
                        ELSE 'FAILED'
                    END,
                    available_at = CASE
                        WHEN attempt_count >= ? THEN available_at
                        ELSE ?
                    END,
                    locked_by = NULL,
                    locked_until = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = ?
                """,
                maxAttempts,
                maxAttempts,
                Timestamp.from(nextAvailableAt),
                error,
                id
        );
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provider_webhook_events", Long.class);
        return count == null ? 0L : count;
    }

    public String statusOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT processing_status FROM provider_webhook_events WHERE id = ?",
                String.class,
                id
        );
    }
}
