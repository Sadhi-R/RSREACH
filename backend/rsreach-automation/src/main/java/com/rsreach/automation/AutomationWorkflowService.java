package com.rsreach.automation;

import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD and lifecycle management for automation workflows.
 *
 * <h3>Version model</h3>
 * Each workflow has one or more {@code automation_version} rows. The version with the
 * highest {@code version_number} is the "latest" version. Draft edits update the latest
 * version in-place (the workflow must be in DRAFT status). Activation publishes the
 * latest version by marking the workflow ACTIVE.
 *
 * <h3>Entitlement</h3>
 * Transitioning from DRAFT to ACTIVE consumes 1 unit of {@link BillingModels#METER_AUTOMATIONS}.
 * Reactivating a PAUSED workflow does not re-consume (same idempotency key).
 */
@Service
public class AutomationWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AutomationWorkflowService.class);

    private final JdbcTemplate jdbcTemplate;
    private final EntitlementService entitlementService;
    private final OutboxRepository outboxRepository;

    public AutomationWorkflowService(
            JdbcTemplate jdbcTemplate,
            EntitlementService entitlementService,
            OutboxRepository outboxRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.entitlementService = entitlementService;
        this.outboxRepository = outboxRepository;
    }

    // -----------------------------------------------------------------------
    // List
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AutomationModels.WorkflowPage list(UUID tenantId, UUID workspaceId, int page, int size) {
        int limit  = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;

        List<AutomationModels.WorkflowView> items = jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, name, status, created_at, updated_at
                FROM automation_workflow
                WHERE tenant_id = ? AND workspace_id = ?
                ORDER BY updated_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> new AutomationModels.WorkflowView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                tenantId, workspaceId, limit + 1, offset
        );

        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new AutomationModels.WorkflowPage(items, page, limit, hasNext);
    }

    // -----------------------------------------------------------------------
    // Get detail
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AutomationModels.WorkflowDetail get(UUID tenantId, UUID workspaceId, UUID id) {
        List<AutomationModels.WorkflowDetail> rows = jdbcTemplate.query(
                """
                SELECT w.id, w.tenant_id, w.workspace_id, w.name, w.status, w.created_at, w.updated_at,
                       v.id AS version_id, v.version_number, v.trigger_type,
                       v.trigger_config::text AS trigger_config_json,
                       v.conditions_json::text AS conditions_json,
                       v.actions_json::text    AS actions_json
                FROM automation_workflow w
                JOIN LATERAL (
                    SELECT id, version_number, trigger_type, trigger_config, conditions_json, actions_json
                    FROM automation_version
                    WHERE workflow_id = w.id
                    ORDER BY version_number DESC
                    LIMIT 1
                ) v ON TRUE
                WHERE w.id = ? AND w.tenant_id = ? AND w.workspace_id = ?
                """,
                (rs, i) -> new AutomationModels.WorkflowDetail(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getObject("version_id", UUID.class),
                        rs.getInt("version_number"),
                        rs.getString("trigger_type"),
                        rs.getString("trigger_config_json"),
                        rs.getString("conditions_json"),
                        rs.getString("actions_json"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                id, tenantId, workspaceId
        );

        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Automation workflow not found");
        }
        return rows.get(0);
    }

    // -----------------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------------

    @Transactional
    public AutomationModels.WorkflowDetail create(
            UUID tenantId,
            UUID workspaceId,
            AutomationModels.CreateWorkflowCommand cmd
    ) {
        requireNonBlank(cmd.name(), "name");
        requireNonBlank(cmd.triggerType(), "triggerType");

        UUID workflowId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO automation_workflow (id, tenant_id, workspace_id, name, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, NOW(), NOW())
                """,
                workflowId, tenantId, workspaceId, cmd.name().trim(), cmd.createdBy()
        );

        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO automation_version (
                    id, workflow_id, tenant_id, workspace_id, version_number,
                    trigger_type, trigger_config, conditions_json, actions_json, created_at
                ) VALUES (?, ?, ?, ?, 1, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), NOW())
                """,
                versionId, workflowId, tenantId, workspaceId,
                cmd.triggerType().trim(),
                blankDefault(cmd.triggerConfigJson(), "{}"),
                blankDefault(cmd.conditionsJson(), "[]"),
                blankDefault(cmd.actionsJson(), "[]")
        );

        log.info("automation_workflow_created tenantId={} workspaceId={} workflowId={}", tenantId, workspaceId, workflowId);
        return get(tenantId, workspaceId, workflowId);
    }

    // -----------------------------------------------------------------------
    // Update draft
    // -----------------------------------------------------------------------

    @Transactional
    public AutomationModels.WorkflowDetail updateDraft(
            UUID tenantId,
            UUID workspaceId,
            UUID workflowId,
            AutomationModels.UpdateWorkflowDraftCommand cmd
    ) {
        AutomationModels.WorkflowDetail existing = get(tenantId, workspaceId, workflowId);
        if (!AutomationModels.STATUS_DRAFT.equals(existing.status())) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Only DRAFT workflows can be edited; workflow status=" + existing.status());
        }
        requireNonBlank(cmd.name(), "name");
        requireNonBlank(cmd.triggerType(), "triggerType");

        jdbcTemplate.update(
                """
                UPDATE automation_workflow
                SET name = ?, updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                cmd.name().trim(), workflowId, tenantId, workspaceId
        );

        jdbcTemplate.update(
                """
                UPDATE automation_version
                SET trigger_type    = ?,
                    trigger_config  = CAST(? AS jsonb),
                    conditions_json = CAST(? AS jsonb),
                    actions_json    = CAST(? AS jsonb)
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                cmd.triggerType().trim(),
                blankDefault(cmd.triggerConfigJson(), "{}"),
                blankDefault(cmd.conditionsJson(), "[]"),
                blankDefault(cmd.actionsJson(), "[]"),
                existing.latestVersionId(), tenantId, workspaceId
        );

        return get(tenantId, workspaceId, workflowId);
    }

    // -----------------------------------------------------------------------
    // Activate
    // -----------------------------------------------------------------------

    @Transactional
    public AutomationModels.WorkflowDetail activate(UUID tenantId, UUID workspaceId, UUID workflowId) {
        AutomationModels.WorkflowDetail existing = get(tenantId, workspaceId, workflowId);
        String currentStatus = existing.status();

        if (AutomationModels.STATUS_ACTIVE.equals(currentStatus)) {
            return existing; // idempotent
        }
        if (AutomationModels.STATUS_ARCHIVED.equals(currentStatus)) {
            throw new ApiException(ErrorCode.CONFLICT, "Archived workflows cannot be reactivated");
        }

        if (AutomationModels.STATUS_DRAFT.equals(currentStatus)) {
            // First-time activation: check and consume AUTOMATIONS entitlement
            BillingModels.CheckResult check = entitlementService.check(
                    tenantId, BillingModels.METER_AUTOMATIONS, 1L);
            if (!check.allowed()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Automation entitlement limit: " + check.reason());
            }
            entitlementService.consume(
                    tenantId, workspaceId,
                    BillingModels.METER_AUTOMATIONS, 1L,
                    "AUTOMATION_ACTIVATE:" + workflowId,
                    "automation_workflow",
                    workflowId.toString()
            );
        }

        jdbcTemplate.update(
                """
                UPDATE automation_workflow
                SET status = 'ACTIVE', updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                workflowId, tenantId, workspaceId
        );

        log.info("automation_workflow_activated tenantId={} workspaceId={} workflowId={}", tenantId, workspaceId, workflowId);
        return get(tenantId, workspaceId, workflowId);
    }

    // -----------------------------------------------------------------------
    // Pause
    // -----------------------------------------------------------------------

    @Transactional
    public AutomationModels.WorkflowDetail pause(UUID tenantId, UUID workspaceId, UUID workflowId) {
        AutomationModels.WorkflowDetail existing = get(tenantId, workspaceId, workflowId);
        if (!AutomationModels.STATUS_ACTIVE.equals(existing.status())) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Only ACTIVE workflows can be paused; status=" + existing.status());
        }

        jdbcTemplate.update(
                """
                UPDATE automation_workflow
                SET status = 'PAUSED', updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                workflowId, tenantId, workspaceId
        );

        log.info("automation_workflow_paused tenantId={} workspaceId={} workflowId={}", tenantId, workspaceId, workflowId);
        return get(tenantId, workspaceId, workflowId);
    }

    // -----------------------------------------------------------------------
    // Archive
    // -----------------------------------------------------------------------

    @Transactional
    public AutomationModels.WorkflowDetail archive(UUID tenantId, UUID workspaceId, UUID workflowId) {
        AutomationModels.WorkflowDetail existing = get(tenantId, workspaceId, workflowId);
        if (AutomationModels.STATUS_ARCHIVED.equals(existing.status())) {
            return existing; // idempotent
        }

        jdbcTemplate.update(
                """
                UPDATE automation_workflow
                SET status = 'ARCHIVED', updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                workflowId, tenantId, workspaceId
        );

        log.info("automation_workflow_archived tenantId={} workspaceId={} workflowId={}", tenantId, workspaceId, workflowId);
        return get(tenantId, workspaceId, workflowId);
    }

    // -----------------------------------------------------------------------
    // Runs
    // -----------------------------------------------------------------------

    /**
     * Returns a page of runs for the given workflow, newest first.
     */
    @Transactional(readOnly = true)
    public AutomationModels.RunPage listRuns(
            UUID tenantId, UUID workspaceId, UUID workflowId, int page, int size) {
        // verify workflow ownership
        get(tenantId, workspaceId, workflowId);
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;

        List<AutomationModels.RunView> items = jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, workflow_id, version_id,
                       status, trigger_type, trigger_payload::text AS trigger_payload_json,
                       depth, contact_id, error_code, started_at, finished_at, created_at
                FROM automation_run
                WHERE tenant_id = ? AND workspace_id = ? AND workflow_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapRun(rs),
                tenantId, workspaceId, workflowId, limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new java.util.ArrayList<>(items.subList(0, limit));
        }
        return new AutomationModels.RunPage(items, page, limit, hasNext);
    }

    /**
     * Returns a single run by id, enforcing tenant and workspace isolation.
     */
    @Transactional(readOnly = true)
    public AutomationModels.RunView getRun(UUID tenantId, UUID workspaceId, UUID runId) {
        List<AutomationModels.RunView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, workflow_id, version_id,
                       status, trigger_type, trigger_payload::text AS trigger_payload_json,
                       depth, contact_id, error_code, started_at, finished_at, created_at
                FROM automation_run
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> mapRun(rs),
                runId, tenantId, workspaceId
        );
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Automation run not found");
        }
        return rows.get(0);
    }

    private static AutomationModels.RunView mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp startedAt = rs.getTimestamp("started_at");
        java.sql.Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new AutomationModels.RunView(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("workflow_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getString("status"),
                rs.getString("trigger_type"),
                rs.getString("trigger_payload_json"),
                rs.getInt("depth"),
                rs.getObject("contact_id", UUID.class),
                rs.getString("error_code"),
                startedAt == null ? null : startedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    // -----------------------------------------------------------------------
    // Enqueue a run (called internally from trigger service)
    // -----------------------------------------------------------------------

    /**
     * Inserts an {@code automation_run} row in QUEUED status and appends an outbox event.
     * Must be called inside an existing transaction.
     */
    @Transactional
    UUID enqueueRun(
            UUID tenantId,
            UUID workspaceId,
            UUID workflowId,
            UUID versionId,
            String triggerType,
            String triggerPayloadJson,
            int depth,
            UUID contactId,
            String outboxIdempotencyKey
    ) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO automation_run (
                    id, tenant_id, workspace_id, workflow_id, version_id,
                    status, trigger_type, trigger_payload, depth, contact_id, created_at
                ) VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, CAST(? AS jsonb), ?, ?, NOW())
                """,
                runId, tenantId, workspaceId, workflowId, versionId,
                triggerType,
                blankDefault(triggerPayloadJson, "{}"),
                depth,
                contactId
        );

        outboxRepository.append(OutboxAppendCommand.of(
                "automation_run",
                runId,
                AutomationModels.EVENT_AUTOMATION_RUN,
                "{\"runId\":\"" + runId + "\"}",
                tenantId,
                workspaceId,
                null,
                outboxIdempotencyKey
        ));

        return runId;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " is required");
        }
    }

    private static String blankDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
