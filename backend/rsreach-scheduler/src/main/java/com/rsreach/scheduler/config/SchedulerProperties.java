package com.rsreach.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsreach.scheduler")
public class SchedulerProperties {

    /**
     * How often the scheduler promotes due work / reclaims leases.
     */
    private long tickIntervalMs = 5000L;

    /**
     * Maximum rows processed per tick.
     */
    private int batchSize = 100;

    public long getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(long tickIntervalMs) {
        this.tickIntervalMs = tickIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
