package com.rsreach.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsreach.worker")
public class WorkerProperties {

    /**
     * Maximum concurrent job handler threads.
     */
    private int concurrency = 4;

    /**
     * Batch size when claiming outbox / job rows.
     */
    private int claimBatchSize = 20;

    /**
     * Polling interval for ready work in milliseconds.
     */
    private long pollIntervalMs = 1000L;

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = claimBatchSize;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }
}
