package com.rsreach.scheduler.runtime;

import com.rsreach.contacts.ContactGroupService;
import com.rsreach.scheduler.config.SchedulerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Periodically enqueues refresh for stale DYNAMIC contact groups.
 * On-demand refresh still happens on create/update/manual refresh API.
 */
@Component
public class ContactGroupRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContactGroupRefreshScheduler.class);

    private final ContactGroupService contactGroupService;
    private final SchedulerProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ScheduledExecutorService executor;

    public ContactGroupRefreshScheduler(
            ContactGroupService contactGroupService,
            SchedulerProperties properties
    ) {
        this.contactGroupService = contactGroupService;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rsreach-group-refresh-scheduler");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = Math.max(30_000L, properties.getTickIntervalMs());
        executor.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("contact_group_refresh_scheduler_started intervalMs={}", intervalMs);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void tick() {
        if (!running.get()) {
            return;
        }
        try {
            int enqueued = contactGroupService.enqueueStaleRefreshes(20);
            if (enqueued > 0) {
                log.info("contact_group_stale_refresh_enqueued count={}", enqueued);
            }
        } catch (Exception ex) {
            log.warn("contact_group_refresh_scheduler_error message={}", ex.getMessage());
        }
    }
}
