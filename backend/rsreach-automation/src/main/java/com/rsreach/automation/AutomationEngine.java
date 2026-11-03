package com.rsreach.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConversationService;
import com.rsreach.contacts.ConsentEvaluationService;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes a single automation run identified by {@code runId}.
 *
 * <h3>Run lifecycle</h3>
 * <ol>
 *   <li>Load run; return immediately if already terminal (COMPLETED/FAILED/SKIPPED).</li>
 *   <li>Consume {@code AUTOMATION_RUNS} entitlement (idempotent on runId).</li>
 *   <li>Mark run RUNNING.</li>
 *   <li>Guard: depth {@literal >} 3 → SKIPPED.</li>
 *   <li>Guard: contact cooldown within 1 h → SKIPPED.</li>
 *   <li>Evaluate conditions_json; failed → SKIPPED.</li>
 *   <li>Execute actions_json in order, logging each to {@code automation_action_log}.</li>
 *   <li>Mark COMPLETED or FAILED.</li>
 *   <li>Upsert cooldown when contact_id is present.</li>
 * </ol>
 *
 * <p>The entire method runs in one transaction so a failure rolls back all partial writes and
 * leaves the run in QUEUED for retry by the outbox poller.
 *
 * <h3>Action params shape (actions_json element)</h3>
 * <pre>{@code
 * {
 *   "type": "SEND_WHATSAPP",
 *   "params": {
 *     "body_text": "Hello!",
 *     "destination_address": "+919876543210",
 *     "purpose": "SERVICE",
 *     "template_name": "hello_world"
 *   }
 * }
 * }</pre>
 */
@Service
public class AutomationEngine {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);

    /** Cooldown window: do not re-run the same workflow for the same contact within this window. */
    private static final int COOLDOWN_HOURS = 1;

    /** Maximum allowed recursion depth for ENQUEUE_WORKFLOW actions. */
    private static final int MAX_DEPTH = 3;

    private final JdbcTemplate jdbcTemplate;
    private final EntitlementService entitlementService;
    private final ConsentEvaluationService consentEvaluationService;
    private final ConversationService conversationService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public AutomationEngine(
            JdbcTemplate jdbcTemplate,
            EntitlementService entitlementService,
            ConsentEvaluationService consentEvaluationService,
            ConversationService conversationService,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.entitlementService = entitlementService;
        this.consentEvaluationService = consentEvaluationService;
        this.conversationService = conversationService;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    @Transactional
    public void processRun(UUID tenantId, UUID workspaceId, UUID runId) {
        RunRecord run = loadRun(tenantId, workspaceId, runId);
        if (run == null) {
            log.warn("automation_run_not_found tenantId={} workspaceId={} runId={}", tenantId, workspaceId, runId);
            return;
        }

        // Idempotency: terminal states are not re-processed
        String status = run.status();
        if (AutomationModels.RUN_COMPLETED.equals(status)
                || AutomationModels.RUN_FAILED.equals(status)
                || AutomationModels.RUN_SKIPPED.equals(status)) {
            log.debug("automation_run_already_terminal runId={} status={}", runId, status);
            return;
        }

        // Consume AUTOMATION_RUNS entitlement (idempotent via idempotency_key)
        try {
            entitlementService.consume(
                    tenantId, workspaceId,
                    BillingModels.METER_AUTOMATION_RUNS, 1L,
                    "AUTOMATION_RUNS:" + runId,
                    "automation_run",
                    runId.toString()
            );
        } catch (ApiException ex) {
            log.warn("automation_run_entitlement_denied runId={} reason={}", runId, ex.getMessage());
            updateRunStatus(tenantId, workspaceId, runId, AutomationModels.RUN_FAILED, "ENTITLEMENT_DENIED");
            return;
        }

        markRunning(tenantId, workspaceId, runId);

        try {
            executeRun(tenantId, workspaceId, runId, run);
        } catch (Exception ex) {
            log.error("automation_run_error runId={} tenantId={} error={}",
                    runId, tenantId, ex.getMessage(), ex);
            updateRunStatus(tenantId, workspaceId, runId, AutomationModels.RUN_FAILED, "EXECUTION_ERROR");
            throw ex; // re-throw so transaction rolls back → run stays QUEUED for retry
        }
    }

    // -----------------------------------------------------------------------
    // Core execution
    // -----------------------------------------------------------------------

    private void executeRun(UUID tenantId, UUID workspaceId, UUID runId, RunRecord run) {
        // Guard: depth limit
        if (run.depth() > MAX_DEPTH) {
            log.info("automation_run_depth_exceeded runId={} depth={}", runId, run.depth());
            updateRunStatus(tenantId, workspaceId, runId, AutomationModels.RUN_SKIPPED, "DEPTH_EXCEEDED");
            return;
        }

        // Guard: contact cooldown (per-workflow, per-contact, within COOLDOWN_HOURS)
        UUID contactId = run.contactId();
        if (contactId != null && isWithinCooldown(tenantId, workspaceId, run.workflowId(), contactId)) {
            log.info("automation_run_cooldown_skip runId={} contactId={}", runId, contactId);
            updateRunStatus(tenantId, workspaceId, runId, AutomationModels.RUN_SKIPPED, "COOLDOWN");
            return;
        }

        // Load version config
        VersionRecord version = loadVersion(run.versionId());
        if (version == null) {
            log.error("automation_version_not_found versionId={}", run.versionId());
            updateRunStatus(tenantId, workspaceId, runId, AutomationModels.RUN_FAILED, "VERSION_NOT_FOUND");
            return;
        }

        // Build context map for condition evaluation
        Map<String, String> context = buildContext(tenantId, workspaceId, contactId, run.triggerPayloadJson());

        // Evaluate conditions
        List<Map<String, Object>> conditions = parseConditions(version.conditionsJson());
        AutomationConditionEvaluator evaluator = new AutomationConditionEvaluator(jdbcTemplate, objectMapper);
        if (!evaluator.evaluateAll(conditions, context, tenantId, workspaceId, contactId)) {
            log.info("automation_run_conditions_failed runId={}", runId);
            updateRunStatus(tenantId, workspaceId, runId, AutomationModels.RUN_SKIPPED, "CONDITIONS_NOT_MET");
            return;
        }

        // Execute actions
        List<Map<String, Object>> actions = parseActions(version.actionsJson());
        for (int i = 0; i < actions.size(); i++) {
            Map<String, Object> action = actions.get(i);
            executeAction(tenantId, workspaceId, runId, run, action, i);
        }

        updateRunStatus(tenantId, workspaceId, runId, AutomationModels.RUN_COMPLETED, null);

        // Upsert cooldown after successful completion
        if (contactId != null) {
            upsertCooldown(tenantId, workspaceId, run.workflowId(), contactId);
        }

        log.info("automation_run_completed runId={} tenantId={}", runId, tenantId);
    }

    // -----------------------------------------------------------------------
    // Action execution
    // -----------------------------------------------------------------------

    private void executeAction(
            UUID tenantId,
            UUID workspaceId,
            UUID runId,
            RunRecord run,
            Map<String, Object> action,
            int stepIndex
    ) {
        String actionType = str(action, "type");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = action.containsKey("params")
                ? (Map<String, Object>) action.get("params")
                : Map.of();

        String actionStatus = AutomationModels.ACTION_SUCCEEDED;
        String detail       = "{}";

        try {
            detail = switch (actionType == null ? "" : actionType) {
                case AutomationModels.ACTION_SEND_WHATSAPP -> executeSend(tenantId, workspaceId, runId, run, params, "WHATSAPP", stepIndex);
                case AutomationModels.ACTION_SEND_EMAIL    -> executeSend(tenantId, workspaceId, runId, run, params, "EMAIL", stepIndex);
                case AutomationModels.ACTION_ADD_TO_GROUP          -> executeAddToGroup(tenantId, workspaceId, run, params);
                case AutomationModels.ACTION_REMOVE_FROM_GROUP     -> executeRemoveFromGroup(tenantId, workspaceId, run, params);
                case AutomationModels.ACTION_UPDATE_CONTACT_FIELD  -> executeUpdateContactField(tenantId, workspaceId, run, params);
                case AutomationModels.ACTION_CREATE_NOTIFICATION   -> executeCreateNotification(run, params);
                case AutomationModels.ACTION_ENQUEUE_WORKFLOW      -> executeEnqueueWorkflow(tenantId, workspaceId, runId, run, params, stepIndex);
                default -> {
                    log.warn("automation_action_unknown_type type={} runId={}", actionType, runId);
                    actionStatus = AutomationModels.ACTION_SKIPPED; // unknown actions are skipped, not failed
                    yield "{\"reason\":\"UNKNOWN_ACTION_TYPE\"}";
                }
            };
        } catch (ApiException ex) {
            log.warn("automation_action_skipped runId={} step={} actionType={} reason={}",
                    runId, stepIndex, actionType, ex.getMessage());
            actionStatus = AutomationModels.ACTION_SKIPPED;
            detail = "{\"reason\":\"" + ex.getMessage().replace("\"", "'") + "\"}";
        } catch (Exception ex) {
            log.error("automation_action_failed runId={} step={} actionType={} error={}",
                    runId, stepIndex, actionType, ex.getMessage(), ex);
            actionStatus = AutomationModels.ACTION_FAILED;
            detail = "{\"error\":\"" + ex.getMessage().replace("\"", "'") + "\"}";
        }

        insertActionLog(tenantId, workspaceId, runId, stepIndex, actionType, actionStatus, detail);
    }

    // -----------------------------------------------------------------------
    // Individual action implementations
    // -----------------------------------------------------------------------

    /**
     * SEND_WHATSAPP / SEND_EMAIL: consent-check then enqueue via ConversationService.
     * Params: body_text, destination_address, purpose (optional, default SERVICE), template_name (optional).
     */
    private String executeSend(
            UUID tenantId, UUID workspaceId, UUID runId,
            RunRecord run, Map<String, Object> params, String channel, int stepIndex
    ) {
        UUID contactId = run.contactId();
        if (contactId == null) {
            log.info("automation_send_skip_no_contact runId={} channel={}", runId, channel);
            return "{\"reason\":\"NO_CONTACT_ON_RUN\"}";
        }

        String bodyText    = str(params, "body_text");
        String destination = str(params, "destination_address");
        String purpose     = str(params, "purpose");
        String templateName = str(params, "template_name");

        if (bodyText == null || bodyText.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "body_text is required for SEND action");
        }
        if (destination == null || destination.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "destination_address is required for SEND action");
        }
        if (purpose == null || purpose.isBlank()) {
            purpose = "SERVICE";
        }

        String consentChannel = "EMAIL".equals(channel) ? "EMAIL" : "WHATSAPP";
        var eligibility = consentEvaluationService.evaluate(
                tenantId, workspaceId, contactId, consentChannel, purpose, destination);
        if (!eligibility.eligible()) {
            log.info("automation_send_consent_denied runId={} contactId={} reason={}",
                    runId, contactId, eligibility.reasonCode());
            // Throw ApiException so executeAction records this action as SKIPPED (not SUCCEEDED).
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "CONSENT_DENIED:" + eligibility.reasonCode());
        }

        UUID conversationId = findOrCreateConversation(tenantId, workspaceId, contactId, channel);
        String idempotencyKey = "AUTOMATION:" + runId + ":" + stepIndex;

        CommunicationModels.MessageView msg = conversationService.composeOutbound(
                tenantId, workspaceId,
                null, // actorId null = system send (bypasses interactive service-window check)
                conversationId,
                new CommunicationModels.ComposeMessageCommand(
                        bodyText, purpose, eligibility.evaluatedAddress(),
                        idempotencyKey, templateName, null)
        );

        return "{\"messageId\":\"" + msg.id() + "\",\"status\":\"" + msg.status() + "\"}";
    }

    /**
     * ADD_TO_GROUP: inserts contact into contact_group_members (MANUAL source).
     * Params: group_id (UUID string).
     */
    private String executeAddToGroup(
            UUID tenantId, UUID workspaceId, RunRecord run, Map<String, Object> params
    ) {
        UUID contactId = run.contactId();
        if (contactId == null) {
            return "{\"reason\":\"NO_CONTACT_ON_RUN\"}";
        }
        UUID groupId = requireUuidParam(params, "group_id");

        jdbcTemplate.update(
                """
                INSERT INTO contact_group_members (id, tenant_id, workspace_id, group_id, contact_id, source, created_at)
                VALUES (?, ?, ?, ?, ?, 'MANUAL', NOW())
                ON CONFLICT (tenant_id, workspace_id, group_id, contact_id) DO NOTHING
                """,
                UUID.randomUUID(), tenantId, workspaceId, groupId, contactId
        );
        return "{\"groupId\":\"" + groupId + "\"}";
    }

    /**
     * REMOVE_FROM_GROUP: removes contact from contact_group_members.
     * Params: group_id (UUID string).
     */
    private String executeRemoveFromGroup(
            UUID tenantId, UUID workspaceId, RunRecord run, Map<String, Object> params
    ) {
        UUID contactId = run.contactId();
        if (contactId == null) {
            return "{\"reason\":\"NO_CONTACT_ON_RUN\"}";
        }
        UUID groupId = requireUuidParam(params, "group_id");

        jdbcTemplate.update(
                """
                DELETE FROM contact_group_members
                WHERE tenant_id = ? AND workspace_id = ? AND group_id = ? AND contact_id = ?
                """,
                tenantId, workspaceId, groupId, contactId
        );
        return "{\"groupId\":\"" + groupId + "\"}";
    }

    /**
     * UPDATE_CONTACT_FIELD: updates a safe set of contact fields.
     * Params: field (supported: display_name), value (string).
     */
    private String executeUpdateContactField(
            UUID tenantId, UUID workspaceId, RunRecord run, Map<String, Object> params
    ) {
        UUID contactId = run.contactId();
        if (contactId == null) {
            return "{\"reason\":\"NO_CONTACT_ON_RUN\"}";
        }
        String field = str(params, "field");
        String value = str(params, "value");
        if (field == null || value == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "UPDATE_CONTACT_FIELD requires field and value params");
        }

        // Supported fields only (prevent arbitrary column injection)
        String sql = switch (field) {
            case "display_name" ->
                    "UPDATE contacts SET display_name = ?, updated_at = NOW() " +
                    "WHERE id = ? AND tenant_id = ? AND home_workspace_id = ? AND status = 'ACTIVE'";
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Unsupported contact field for automation update: " + field);
        };

        int rows = jdbcTemplate.update(sql, value, contactId, tenantId, workspaceId);
        return "{\"field\":\"" + field + "\",\"updated\":" + rows + "}";
    }

    /**
     * CREATE_NOTIFICATION: inserts a row into the notifications table so the user sees it in the
     * in-app notification centre.
     *
     * <p>Params:
     * <ul>
     *   <li>{@code title}     – notification heading (required, falls back to "Automation notification")</li>
     *   <li>{@code message}   – notification body text (optional)</li>
     *   <li>{@code link_path} – relative URL the notification links to (optional)</li>
     *   <li>{@code user_id}   – UUID string of the target user; defaults to the run's owning user when null</li>
     * </ul>
     */
    private String executeCreateNotification(RunRecord run, Map<String, Object> params) {
        String title = str(params, "title");
        if (title == null || title.isBlank()) {
            title = "Automation notification";
        }
        String message  = str(params, "message");
        String linkPath = str(params, "link_path");

        // Resolve target userId: explicit param → null (tenant-broadcast)
        UUID userId = null;
        String userIdParam = str(params, "user_id");
        if (userIdParam != null && !userIdParam.isBlank()) {
            try {
                userId = UUID.fromString(userIdParam);
            } catch (IllegalArgumentException ex) {
                log.warn("automation_notification_bad_user_id runId={} value={}", run.runId(), userIdParam);
            }
        }

        UUID notificationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notifications
                    (id, tenant_id, workspace_id, user_id, title, body, link_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                notificationId, run.tenantId(), run.workspaceId(), userId, title, message, linkPath
        );
        log.debug("automation_notification_created id={} runId={} userId={}", notificationId, run.runId(), userId);
        return "{\"notificationId\":\"" + notificationId + "\"}";
    }

    /**
     * ENQUEUE_WORKFLOW: enqueue another workflow with depth+1 (max depth guard already checked).
     * Params: workflow_id (UUID string).
     */
    private String executeEnqueueWorkflow(
            UUID tenantId, UUID workspaceId, UUID runId,
            RunRecord run, Map<String, Object> params, int stepIndex
    ) {
        int nextDepth = run.depth() + 1;
        if (nextDepth > MAX_DEPTH) {
            log.info("automation_enqueue_depth_limit runId={} nextDepth={}", runId, nextDepth);
            return "{\"skipped\":true,\"reason\":\"DEPTH_LIMIT\"}";
        }

        UUID targetWorkflowId = requireUuidParam(params, "workflow_id");

        // Load the target workflow's latest version
        List<UUID[]> versionRows = jdbcTemplate.query(
                """
                SELECT id, version_number
                FROM automation_version
                WHERE workflow_id = ? AND tenant_id = ? AND workspace_id = ?
                ORDER BY version_number DESC
                LIMIT 1
                """,
                (rs, i) -> new UUID[]{rs.getObject("id", UUID.class)},
                targetWorkflowId, tenantId, workspaceId
        );
        if (versionRows.isEmpty()) {
            log.warn("automation_enqueue_no_version workflowId={} runId={}", targetWorkflowId, runId);
            throw new ApiException(ErrorCode.NOT_FOUND,
                    "Target workflow not found or has no versions: " + targetWorkflowId);
        }
        UUID targetVersionId = versionRows.get(0)[0];

        UUID newRunId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO automation_run (
                    id, tenant_id, workspace_id, workflow_id, version_id,
                    status, trigger_type, trigger_payload, depth, contact_id, created_at
                ) VALUES (?, ?, ?, ?, ?, 'QUEUED', 'ENQUEUE_WORKFLOW', '{}'::jsonb, ?, ?, NOW())
                """,
                newRunId, tenantId, workspaceId, targetWorkflowId, targetVersionId,
                nextDepth, run.contactId()
        );

        outboxRepository.append(OutboxAppendCommand.of(
                "automation_run",
                newRunId,
                AutomationModels.EVENT_AUTOMATION_RUN,
                "{\"runId\":\"" + newRunId + "\"}",
                tenantId,
                workspaceId,
                null,
                "AUTOMATION_RUN:" + newRunId
        ));

        return "{\"enqueuedRunId\":\"" + newRunId + "\",\"depth\":" + nextDepth + "}";
    }

    // -----------------------------------------------------------------------
    // DB helpers
    // -----------------------------------------------------------------------

    private RunRecord loadRun(UUID tenantId, UUID workspaceId, UUID runId) {
        List<RunRecord> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, workflow_id, version_id,
                       status, depth, contact_id, trigger_payload::text AS trigger_payload_json
                FROM automation_run
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> new RunRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("workflow_id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("depth"),
                        rs.getObject("contact_id", UUID.class),
                        rs.getString("trigger_payload_json")
                ),
                runId, tenantId, workspaceId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private VersionRecord loadVersion(UUID versionId) {
        List<VersionRecord> rows = jdbcTemplate.query(
                """
                SELECT id, conditions_json::text AS conditions_json,
                       actions_json::text AS actions_json
                FROM automation_version
                WHERE id = ?
                """,
                (rs, i) -> new VersionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("conditions_json"),
                        rs.getString("actions_json")
                ),
                versionId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void markRunning(UUID tenantId, UUID workspaceId, UUID runId) {
        jdbcTemplate.update(
                """
                UPDATE automation_run
                SET status = 'RUNNING', started_at = NOW(), updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                """,
                runId, tenantId, workspaceId
        );
    }

    private void updateRunStatus(UUID tenantId, UUID workspaceId, UUID runId, String status, String errorCode) {
        jdbcTemplate.update(
                """
                UPDATE automation_run
                SET status = ?, error_code = ?, finished_at = NOW(), updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                status, errorCode, runId, tenantId, workspaceId
        );
    }

    private void insertActionLog(
            UUID tenantId, UUID workspaceId, UUID runId,
            int stepIndex, String actionType, String status, String detailJson
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO automation_action_log (
                    id, run_id, tenant_id, workspace_id, step_index, action_type, status, detail_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), NOW())
                """,
                UUID.randomUUID(), runId, tenantId, workspaceId,
                stepIndex, actionType, status, detailJson
        );
    }

    private boolean isWithinCooldown(UUID tenantId, UUID workspaceId, UUID workflowId, UUID contactId) {
        Instant cutoff = Instant.now().minus(COOLDOWN_HOURS, ChronoUnit.HOURS);
        List<Instant> rows = jdbcTemplate.query(
                """
                SELECT last_run_at FROM automation_cooldown
                WHERE tenant_id = ? AND workspace_id = ? AND workflow_id = ? AND contact_id = ?
                """,
                (rs, i) -> rs.getTimestamp("last_run_at").toInstant(),
                tenantId, workspaceId, workflowId, contactId
        );
        return !rows.isEmpty() && rows.get(0).isAfter(cutoff);
    }

    private void upsertCooldown(UUID tenantId, UUID workspaceId, UUID workflowId, UUID contactId) {
        jdbcTemplate.update(
                """
                INSERT INTO automation_cooldown (id, tenant_id, workspace_id, workflow_id, contact_id, last_run_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (tenant_id, workspace_id, workflow_id, contact_id)
                DO UPDATE SET last_run_at = NOW()
                """,
                UUID.randomUUID(), tenantId, workspaceId, workflowId, contactId
        );
    }

    /**
     * Finds the most recent OPEN conversation for the contact+channel, or creates one.
     */
    private UUID findOrCreateConversation(UUID tenantId, UUID workspaceId, UUID contactId, String channel) {
        List<UUID> rows = jdbcTemplate.query(
                """
                SELECT id FROM conversations
                WHERE tenant_id = ? AND workspace_id = ? AND contact_id = ? AND channel = ? AND status = 'OPEN'
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                tenantId, workspaceId, contactId, channel
        );
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        CommunicationModels.ConversationView conv = conversationService.create(
                tenantId, workspaceId, null,
                new CommunicationModels.CreateConversationCommand(channel, contactId, null, null, null)
        );
        return conv.id();
    }

    private Map<String, String> buildContext(
            UUID tenantId, UUID workspaceId, UUID contactId, String triggerPayloadJson
    ) {
        Map<String, String> context = new HashMap<>();

        // Flatten trigger payload fields
        if (triggerPayloadJson != null && !triggerPayloadJson.isBlank()) {
            try {
                Map<String, Object> payload = objectMapper.readValue(
                        triggerPayloadJson, new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> e : payload.entrySet()) {
                    if (e.getValue() != null) {
                        context.put(e.getKey(), e.getValue().toString());
                    }
                }
            } catch (Exception ex) {
                log.warn("automation_context_payload_parse_error error={}", ex.getMessage());
            }
        }

        // Add contact fields
        if (contactId != null) {
            List<Map<String, String>> contactRows = jdbcTemplate.query(
                    """
                    SELECT display_name, status,
                           primary_email_normalized, primary_phone_e164
                    FROM contacts
                    WHERE id = ? AND tenant_id = ?
                    """,
                    (rs, i) -> {
                        Map<String, String> m = new HashMap<>();
                        m.put("contact.display_name", rs.getString("display_name"));
                        m.put("contact.status",       rs.getString("status"));
                        m.put("contact.primary_email", rs.getString("primary_email_normalized"));
                        m.put("contact.primary_phone", rs.getString("primary_phone_e164"));
                        return m;
                    },
                    contactId, tenantId
            );
            if (!contactRows.isEmpty()) {
                contactRows.get(0).forEach((k, v) -> {
                    if (v != null) {
                        context.put(k, v);
                    }
                });
            }
        }

        return context;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseConditions(String conditionsJson) {
        if (conditionsJson == null || conditionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(conditionsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ex) {
            log.warn("automation_conditions_parse_error error={}", ex.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseActions(String actionsJson) {
        if (actionsJson == null || actionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(actionsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ex) {
            log.warn("automation_actions_parse_error error={}", ex.getMessage());
            return List.of();
        }
    }

    private static UUID requireUuidParam(Map<String, Object> params, String key) {
        String v = str(params, key);
        if (v == null || v.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, key + " param is required");
        }
        try {
            return UUID.fromString(v.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, key + " is not a valid UUID: " + v);
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    // -----------------------------------------------------------------------
    // Inner record types (package-private)
    // -----------------------------------------------------------------------

    record RunRecord(
            UUID runId,
            UUID tenantId,
            UUID workspaceId,
            UUID workflowId,
            UUID versionId,
            String status,
            int depth,
            UUID contactId,
            String triggerPayloadJson
    ) {
    }

    record VersionRecord(
            UUID versionId,
            String conditionsJson,
            String actionsJson
    ) {
    }
}
