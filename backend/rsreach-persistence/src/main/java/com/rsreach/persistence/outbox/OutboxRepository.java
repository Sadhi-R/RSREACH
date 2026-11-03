package com.rsreach.persistence.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC outbox access. Callers MUST invoke {@link #append(OutboxAppendCommand)} in the same
 * Spring transaction as the business write so rollbacks leave no orphaned outbox rows.
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public UUID append(OutboxAppendCommand command) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload_json,
                    tenant_id, workspace_id, correlation_id, idempotency_key,
                    status, attempt_count, max_attempts, available_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, 'PENDING', 0, ?, NOW(), NOW(), NOW())
                """,
                id,
                command.aggregateType(),
                command.aggregateId(),
                command.eventType(),
                command.payloadJson(),
                command.tenantId(),
                command.workspaceId(),
                command.correlationId(),
                command.idempotencyKey(),
                command.maxAttempts()
        );
        return id;
    }

    /**
     * Claims due outbox rows using SKIP LOCKED for concurrent workers.
     * Expired PROCESSING leases are reclaimable.
     */
    @Transactional
    public List<UUID> claimBatch(String workerId, int batchSize, Instant now) {
        Timestamp nowTs = Timestamp.from(now);
        return jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT id
                    FROM outbox_events
                    WHERE (
                            status = 'PENDING'
                            AND available_at <= ?
                        )
                        OR (
                            status = 'PROCESSING'
                            AND locked_until IS NOT NULL
                            AND locked_until < ?
                        )
                    ORDER BY available_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE outbox_events o
                SET status = 'PROCESSING',
                    locked_by = ?,
                    locked_until = ?,
                    attempt_count = o.attempt_count + 1,
                    updated_at = NOW()
                FROM candidates c
                WHERE o.id = c.id
                RETURNING o.id
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                nowTs,
                nowTs,
                batchSize,
                workerId,
                Timestamp.from(now.plusSeconds(60))
        );
    }

    @Transactional
    public void markProcessed(UUID id) {
        jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PROCESSED',
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
    public void markRetryOrDead(UUID id, String error, Instant nextAvailableAt) {
        jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = CASE
                        WHEN attempt_count >= max_attempts THEN 'DEAD'
                        ELSE 'PENDING'
                    END,
                    available_at = CASE
                        WHEN attempt_count >= max_attempts THEN available_at
                        ELSE ?
                    END,
                    locked_by = NULL,
                    locked_until = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = ?
                """,
                Timestamp.from(nextAvailableAt),
                error,
                id
        );
    }

    public java.util.Optional<OutboxEvent> findById(UUID id) {
        java.util.List<OutboxEvent> rows = jdbcTemplate.query(
                """
                SELECT id, aggregate_type, aggregate_id, event_type, payload_json::text,
                       tenant_id, workspace_id, correlation_id, idempotency_key,
                       status, attempt_count, max_attempts, available_at, processed_at, last_error
                FROM outbox_events
                WHERE id = ?
                """,
                (rs, i) -> new OutboxEvent(
                        (UUID) rs.getObject("id"),
                        rs.getString("aggregate_type"),
                        (UUID) rs.getObject("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload_json"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("workspace_id"),
                        rs.getString("correlation_id"),
                        rs.getString("idempotency_key"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getTimestamp("available_at").toInstant(),
                        rs.getTimestamp("processed_at") == null
                                ? null
                                : rs.getTimestamp("processed_at").toInstant(),
                        rs.getString("last_error")
                ),
                id
        );
        return rows.stream().findFirst();
    }

    public long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = ?",
                Long.class,
                status
        );
        return count == null ? 0L : count;
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);
        return count == null ? 0L : count;
    }
}
