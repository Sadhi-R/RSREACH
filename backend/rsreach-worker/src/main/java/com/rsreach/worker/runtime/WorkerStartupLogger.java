package com.rsreach.worker.runtime;

import com.rsreach.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Phase 0: confirms worker boot and configured concurrency.
 * Job claiming/handlers arrive with the scheduling module.
 */
@Component
public class WorkerStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerStartupLogger.class);

    private final WorkerProperties workerProperties;

    public WorkerStartupLogger(WorkerProperties workerProperties) {
        this.workerProperties = workerProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info(
                "RSReach worker ready (phase=0, concurrency={}, claimBatchSize={}, pollIntervalMs={})",
                workerProperties.getConcurrency(),
                workerProperties.getClaimBatchSize(),
                workerProperties.getPollIntervalMs()
        );
    }
}
