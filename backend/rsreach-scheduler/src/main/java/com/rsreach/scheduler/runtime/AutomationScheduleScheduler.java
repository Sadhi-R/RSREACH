package com.rsreach.scheduler.runtime;

import com.rsreach.automation.AutomationModels;
import com.rsreach.automation.AutomationTriggerService;
import com.rsreach.scheduler.config.SchedulerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Every minute: find ACTIVE automation workflows whose latest version has trigger_type=SCHEDULE
 * and a {@code dueAt} instant in the past, then fire them via {@link AutomationTriggerService}.
 *
 * <h3>trigger_config shape (SCHEDULE workflows)</h3>
 * <pre>{@code
 * {
 *   "dueAt": "2026-11-03T10:00:00Z",      // ISO-8601 UTC instant
 *   "intervalMinutes": 60                   // optional; if present, dueAt is bumped after each fire
 * }
 * }</pre>
 *
 * <p>If {@code intervalMinutes} is present, {@code dueAt} is advanced by that many minutes after
 * each successful fire so the workflow repeats. If absent, {@code dueAt} is left unchanged; the
 * workflow will keep matching until the user updates or archives it — this is an acknowledged
 * Phase 5 MVP limitation (cooldown logic in {@link com.rsreach.automation.AutomationEngine}
 * limits spam for contact-scoped runs).
 */
@Component
public class AutomationScheduleScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationScheduleScheduler.class);

    private static final long TICK_INTERVAL_MS = 60_000L;

    private final AutomationTriggerService automationTriggerService;
    private final JdbcTemplate jdbcTemplate;
    private final SchedulerProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ScheduledExecutorService executor;

    public AutomationScheduleScheduler(
            AutomationTriggerService automationTriggerService,
            JdbcTemplate jdbcTemplate,
            SchedulerProperties properties
    ) {
        this.automationTriggerService = automationTriggerService;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rsreach-automation-schedule-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("automation_schedule_scheduler_started intervalMs={}", TICK_INTERVAL_MS);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Tick
    // -----------------------------------------------------------------------

    void tick() {
        if (!running.get()) {
            return;
        }
        try {
            List<DueSchedule> due = findDueSchedules();
            if (!due.isEmpty()) {
                log.info("automation_schedule_due count={}", due.size());
            }
            for (DueSchedule schedule : due) {
                fireSchedule(schedule);
            }
        } catch (Exception ex) {
            log.warn("automation_schedule_scheduler_error message={}", ex.getMessage(), ex);
        }
    }

    private void fireSchedule(DueSchedule schedule) {
        try {
            String payload = "{\"scheduledAt\":\"" + Instant.now() + "\"}";
            automationTriggerService.onDomainEvent(
                    schedule.tenantId(),
                    schedule.workspaceId(),
                    AutomationModels.TRIGGER_SCHEDULE,
                    null,   // no specific contact for schedule triggers
                    payload
            );
            // Bump dueAt if intervalMinutes is configured (repeating schedule).
            if (schedule.intervalMinutes() != null) {
                bumpDueAt(schedule.versionId(), schedule.intervalMinutes());
            }
            log.debug("automation_schedule_fired workflowId={} versionId={}",
                    schedule.workflowId(), schedule.versionId());
        } catch (Exception ex) {
            log.warn("automation_schedule_fire_error workflowId={} error={}",
                    schedule.workflowId(), ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // DB helpers
    // -----------------------------------------------------------------------

    /**
     * Returns all ACTIVE workflows whose latest version has trigger_type=SCHEDULE,
     * a {@code dueAt} key in trigger_config, and that dueAt is in the past.
     */
    private List<DueSchedule> findDueSchedules() {
        return jdbcTemplate.query(
                """
                SELECT w.id            AS workflow_id,
                       w.tenant_id,
                       w.workspace_id,
                       v.id            AS version_id,
                       v.trigger_config->>'intervalMinutes' AS interval_minutes
                FROM automation_workflow w
                JOIN LATERAL (
                    SELECT id, trigger_type, trigger_config
                    FROM automation_version
                    WHERE workflow_id = w.id
                    ORDER BY version_number DESC
                    LIMIT 1
                ) v ON v.trigger_type = 'SCHEDULE'
                    AND v.trigger_config ? 'dueAt'
                    AND (v.trigger_config->>'dueAt')::timestamptz <= NOW()
                WHERE w.status = 'ACTIVE'
                """,
                (rs, i) -> {
                    String intervalStr = rs.getString("interval_minutes");
                    Long intervalMinutes = null;
                    if (intervalStr != null && !intervalStr.isBlank()) {
                        try {
                            intervalMinutes = Long.parseLong(intervalStr.trim());
                        } catch (NumberFormatException e) {
                            log.warn("automation_schedule_invalid_interval value={}", intervalStr);
                        }
                    }
                    return new DueSchedule(
                            rs.getObject("workflow_id",  UUID.class),
                            rs.getObject("tenant_id",    UUID.class),
                            rs.getObject("workspace_id", UUID.class),
                            rs.getObject("version_id",   UUID.class),
                            intervalMinutes
                    );
                }
        );
    }

    /**
     * Advances {@code dueAt} in the version's trigger_config by {@code intervalMinutes}.
     * Runs in a separate transaction from the trigger fire — if this update fails the scheduler
     * will re-fire next minute (acceptable MVP behaviour).
     */
    private void bumpDueAt(UUID versionId, long intervalMinutes) {
        jdbcTemplate.update(
                """
                UPDATE automation_version
                SET trigger_config = jsonb_set(
                    trigger_config,
                    '{dueAt}',
                    to_jsonb(to_char(
                        (trigger_config->>'dueAt')::timestamptz
                            + (? * interval '1 minute'),
                        'YYYY-MM-DD"T"HH24:MI:SS"Z"'
                    ))
                )
                WHERE id = ?
                """,
                intervalMinutes, versionId
        );
    }

    // -----------------------------------------------------------------------
    // Inner record
    // -----------------------------------------------------------------------

    record DueSchedule(
            UUID workflowId,
            UUID tenantId,
            UUID workspaceId,
            UUID versionId,
            Long intervalMinutes
    ) {
    }
}
