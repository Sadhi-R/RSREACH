package com.rsreach.automation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared constants and immutable view/command records for the automation domain.
 *
 * <p>All condition JSON objects have shape {@code {field, op, value}}.
 * Action JSON objects have shape {@code {type, params: {...}}}.
 */
public final class AutomationModels {

    private AutomationModels() {
    }

    // -----------------------------------------------------------------------
    // Outbox event types
    // -----------------------------------------------------------------------

    /** Outbox event that triggers processing of one automation run. */
    public static final String EVENT_AUTOMATION_RUN    = "AUTOMATION_RUN";
    /** Outbox event for individual action execution (reserved for future async steps). */
    public static final String EVENT_AUTOMATION_ACTION = "AUTOMATION_ACTION";

    // -----------------------------------------------------------------------
    // Workflow statuses — must match automation_workflow.status CHECK constraint
    // -----------------------------------------------------------------------

    public static final String STATUS_DRAFT    = "DRAFT";
    public static final String STATUS_ACTIVE   = "ACTIVE";
    public static final String STATUS_PAUSED   = "PAUSED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    // -----------------------------------------------------------------------
    // Run statuses — must match automation_run.status CHECK constraint
    // -----------------------------------------------------------------------

    public static final String RUN_QUEUED    = "QUEUED";
    public static final String RUN_RUNNING   = "RUNNING";
    public static final String RUN_COMPLETED = "COMPLETED";
    public static final String RUN_FAILED    = "FAILED";
    public static final String RUN_SKIPPED   = "SKIPPED";

    // -----------------------------------------------------------------------
    // Action log statuses — must match automation_action_log.status CHECK constraint
    // -----------------------------------------------------------------------

    public static final String ACTION_PENDING   = "PENDING";
    public static final String ACTION_SUCCEEDED = "SUCCEEDED";
    public static final String ACTION_FAILED    = "FAILED";
    public static final String ACTION_SKIPPED   = "SKIPPED";

    // -----------------------------------------------------------------------
    // Trigger types — must match automation_version.trigger_type values
    // -----------------------------------------------------------------------

    public static final String TRIGGER_CONTACT_CREATED     = "CONTACT_CREATED";
    public static final String TRIGGER_CONTACT_UPDATED     = "CONTACT_UPDATED";
    public static final String TRIGGER_CONTACT_GROUP_ADDED = "CONTACT_GROUP_ADDED";
    public static final String TRIGGER_CONSENT_CHANGED     = "CONSENT_CHANGED";
    public static final String TRIGGER_INBOUND_MESSAGE     = "INBOUND_MESSAGE";
    public static final String TRIGGER_CAMPAIGN_COMPLETED  = "CAMPAIGN_COMPLETED";
    public static final String TRIGGER_SCHEDULE            = "SCHEDULE";
    public static final String TRIGGER_INBOUND_WEBHOOK     = "INBOUND_WEBHOOK";

    // -----------------------------------------------------------------------
    // Action types — must match type values in actions_json entries
    // -----------------------------------------------------------------------

    public static final String ACTION_SEND_WHATSAPP        = "SEND_WHATSAPP";
    public static final String ACTION_SEND_EMAIL           = "SEND_EMAIL";
    public static final String ACTION_ADD_TO_GROUP         = "ADD_TO_GROUP";
    public static final String ACTION_REMOVE_FROM_GROUP    = "REMOVE_FROM_GROUP";
    public static final String ACTION_UPDATE_CONTACT_FIELD = "UPDATE_CONTACT_FIELD";
    public static final String ACTION_CREATE_NOTIFICATION  = "CREATE_NOTIFICATION";
    public static final String ACTION_ENQUEUE_WORKFLOW     = "ENQUEUE_WORKFLOW";

    // -----------------------------------------------------------------------
    // View records (read models)
    // -----------------------------------------------------------------------

    public record WorkflowView(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String name,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record WorkflowDetail(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String name,
            String status,
            UUID latestVersionId,
            int latestVersionNumber,
            String triggerType,
            String triggerConfigJson,
            String conditionsJson,
            String actionsJson,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record RunView(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID workflowId,
            UUID versionId,
            String status,
            String triggerType,
            String triggerPayloadJson,
            int depth,
            UUID contactId,
            String errorCode,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt
    ) {
    }

    public record ActionLogView(
            UUID id,
            UUID runId,
            int stepIndex,
            String actionType,
            String status,
            String detailJson,
            Instant createdAt
    ) {
    }

    // -----------------------------------------------------------------------
    // Command records
    // -----------------------------------------------------------------------

    public record CreateWorkflowCommand(
            String name,
            String triggerType,
            String triggerConfigJson,
            String conditionsJson,
            String actionsJson,
            UUID createdBy
    ) {
    }

    public record UpdateWorkflowDraftCommand(
            String name,
            String triggerType,
            String triggerConfigJson,
            String conditionsJson,
            String actionsJson
    ) {
    }

    // -----------------------------------------------------------------------
    // Pagination
    // -----------------------------------------------------------------------

    public record WorkflowPage(List<WorkflowView> items, int page, int size, boolean hasNext) {
    }

    public record RunPage(List<RunView> items, int page, int size, boolean hasNext) {
    }
}
