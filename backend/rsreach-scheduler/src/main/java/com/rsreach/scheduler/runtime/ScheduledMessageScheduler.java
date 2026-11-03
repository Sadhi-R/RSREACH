package com.rsreach.scheduler.runtime;

import com.rsreach.communications.CommunicationModels;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ScheduledMessageScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final OutboxRepository outboxRepository;

    public ScheduledMessageScheduler(JdbcTemplate jdbcTemplate, OutboxRepository outboxRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(fixedDelayString = "${rsreach.scheduler.scheduled-messages-ms:30000}")
    @Transactional
    public void fireDue() {
        List<DueRow> due = jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id FROM scheduled_messages
                WHERE status = 'PENDING' AND due_at <= NOW()
                ORDER BY due_at
                LIMIT 20
                FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> new DueRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("workspace_id")
                )
        );
        for (DueRow row : due) {
            jdbcTemplate.update(
                    """
                    UPDATE scheduled_messages
                    SET status = 'FIRED', fired_at = NOW(), updated_at = NOW()
                    WHERE id = ? AND status = 'PENDING'
                    """,
                    row.id()
            );
            outboxRepository.append(OutboxAppendCommand.of(
                    "scheduled_message",
                    row.id(),
                    CommunicationModels.EVENT_SCHEDULED_MESSAGE_FIRE,
                    "{\"scheduledMessageId\":\"" + row.id() + "\"}",
                    row.tenantId(),
                    row.workspaceId(),
                    null,
                    "SCHEDULED_MESSAGE_FIRE:" + row.id()
            ));
            log.info("scheduled_message_fired id={}", row.id());
        }
    }

    private record DueRow(UUID id, UUID tenantId, UUID workspaceId) {
    }
}
