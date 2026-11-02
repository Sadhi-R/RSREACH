package com.rsreach.scheduler.runtime;

import com.rsreach.scheduler.config.SchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Phase 0: confirms scheduler boot and tick configuration.
 * Recurrence expansion and lease reclaim arrive with the scheduling module.
 */
@Component
public class SchedulerStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchedulerStartupLogger.class);

    private final SchedulerProperties schedulerProperties;

    public SchedulerStartupLogger(SchedulerProperties schedulerProperties) {
        this.schedulerProperties = schedulerProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info(
                "RSReach scheduler ready (phase=0, tickIntervalMs={}, batchSize={})",
                schedulerProperties.getTickIntervalMs(),
                schedulerProperties.getBatchSize()
        );
    }
}
