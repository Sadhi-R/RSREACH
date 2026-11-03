package com.rsreach.automation;

import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles domain events and enqueues automation runs for matching active workflows.
 *
 * <h3>Usage</h3>
 * Domain services call {@link #onDomainEvent} inside their own transaction so that the
 * automation_run inserts and outbox appends are atomic with the originating business write.
 *
 * <h3>Idempotency</h3>
 * Each outbox entry uses a key of the form
 * {@code AUTOMATION_TRIGGER:<workflowId>:<triggerType>:<contactId>:<runId>}.
 * Because {@code runId} is freshly generated per call, duplicate prevention is handled by
 * the caller (e.g., callers should not emit the same event twice without a new event key).
 * Cooldown and condition guards in {@link AutomationEngine} prevent spurious re-execution.
 *
 * <h3>Trigger config filtering</h3>
 * The {@code trigger_config} JSONB column is reserved for future sub-type filters
 * (e.g., which group_id for CONTACT_GROUP_ADDED). This implementation matches on
 * {@code trigger_type} only; callers supply {@code payloadJson} for runtime context.
 */
@Service
public class AutomationTriggerService {

    private static final Logger log = LoggerFactory.getLogger(AutomationTriggerService.class);

    private final JdbcTemplate jdbcTemplate;
    private final OutboxRepository outboxRepository;

    public AutomationTriggerService(
            JdbcTemplate jdbcTemplate,
            OutboxRepository outboxRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Matches all ACTIVE workflows in the workspace whose latest version has the given
     * {@code triggerType}, inserts an {@code automation_run} row for each, and appends
     * an outbox event to be processed by {@link AutomationEngine}.
     *
     * <p>Must be called inside an existing transaction so the inserts are atomic with the
     * originating domain event.
     *
     * @param tenantId    tenant isolation key
     * @param workspaceId workspace isolation key
     * @param triggerType one of the {@code AutomationModels.TRIGGER_*} constants
     * @param contactId   nullable; the contact that triggered the event
     * @param payloadJson free-form JSON context for condition evaluation (must be valid JSON)
     */
    @Transactional
    public void onDomainEvent(
            UUID tenantId,
            UUID workspaceId,
            String triggerType,
            UUID contactId,
            String payloadJson
    ) {
        if (tenantId == null || workspaceId == null) {
            log.warn("automation_trigger_missing_context triggerType={}", triggerType);
            return;
        }
        if (triggerType == null || triggerType.isBlank()) {
            log.warn("automation_trigger_missing_type tenantId={}", tenantId);
            return;
        }

        String effectivePayload = (payloadJson == null || payloadJson.isBlank()) ? "{}" : payloadJson.trim();

        // Find all ACTIVE workflows whose latest version matches the trigger type
        List<WorkflowVersionPair> matches = findMatchingWorkflows(tenantId, workspaceId, triggerType);

        if (matches.isEmpty()) {
            log.debug("automation_trigger_no_matches tenantId={} workspaceId={} triggerType={}",
                    tenantId, workspaceId, triggerType);
            return;
        }

        log.info("automation_trigger_matched tenantId={} workspaceId={} triggerType={} count={}",
                tenantId, workspaceId, triggerType, matches.size());

        for (WorkflowVersionPair pair : matches) {
            UUID runId = UUID.randomUUID();
            String idempotencyKey = "AUTOMATION_TRIGGER:"
                    + pair.workflowId() + ":"
                    + triggerType + ":"
                    + (contactId != null ? contactId : "NONE")
                    + ":" + runId;

            insertRun(tenantId, workspaceId, runId, pair.workflowId(), pair.versionId(),
                    triggerType, effectivePayload, 0, contactId);

            outboxRepository.append(OutboxAppendCommand.of(
                    "automation_run",
                    runId,
                    AutomationModels.EVENT_AUTOMATION_RUN,
                    "{\"runId\":\"" + runId + "\"}",
                    tenantId,
                    workspaceId,
                    null,
                    idempotencyKey
            ));

            log.debug("automation_run_enqueued tenantId={} workflowId={} runId={}",
                    tenantId, pair.workflowId(), runId);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the workflow+version pairs for all ACTIVE workflows in the workspace whose
     * latest version matches {@code triggerType}.
     */
    private List<WorkflowVersionPair> findMatchingWorkflows(
            UUID tenantId, UUID workspaceId, String triggerType
    ) {
        return jdbcTemplate.query(
                """
                SELECT w.id AS workflow_id, v.id AS version_id
                FROM automation_workflow w
                JOIN LATERAL (
                    SELECT id, trigger_type
                    FROM automation_version
                    WHERE workflow_id = w.id
                    ORDER BY version_number DESC
                    LIMIT 1
                ) v ON v.trigger_type = ?
                WHERE w.tenant_id = ?
                  AND w.workspace_id = ?
                  AND w.status = 'ACTIVE'
                """,
                (rs, i) -> new WorkflowVersionPair(
                        rs.getObject("workflow_id", UUID.class),
                        rs.getObject("version_id", UUID.class)
                ),
                triggerType, tenantId, workspaceId
        );
    }

    private void insertRun(
            UUID tenantId, UUID workspaceId, UUID runId,
            UUID workflowId, UUID versionId,
            String triggerType, String payloadJson,
            int depth, UUID contactId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO automation_run (
                    id, tenant_id, workspace_id, workflow_id, version_id,
                    status, trigger_type, trigger_payload, depth, contact_id, created_at
                ) VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, CAST(? AS jsonb), ?, ?, NOW())
                """,
                runId, tenantId, workspaceId, workflowId, versionId,
                triggerType, payloadJson, depth, contactId
        );
    }

    // -----------------------------------------------------------------------
    // Inner record
    // -----------------------------------------------------------------------

    private record WorkflowVersionPair(UUID workflowId, UUID versionId) {
    }
}
