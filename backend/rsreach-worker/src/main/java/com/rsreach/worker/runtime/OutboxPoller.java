package com.rsreach.worker.runtime;

import com.rsreach.persistence.outbox.OutboxEvent;
import com.rsreach.persistence.outbox.OutboxRepository;
import com.rsreach.worker.config.WorkerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outboxRepository;
    private final OutboxEventDispatcher dispatcher;
    private final WorkerProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String workerId = "worker-" + UUID.randomUUID();
    private ExecutorService pollExecutor;

    public OutboxPoller(
            OutboxRepository outboxRepository,
            OutboxEventDispatcher dispatcher,
            WorkerProperties properties
    ) {
        this.outboxRepository = outboxRepository;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        pollExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rsreach-outbox-poller");
            t.setDaemon(true);
            return t;
        });
        pollExecutor.submit(this::loop);
        log.info("outbox_poller_started workerId={} batchSize={} pollMs={}",
                workerId, properties.getClaimBatchSize(), properties.getPollIntervalMs());
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                List<UUID> claimed = outboxRepository.claimBatch(
                        workerId, properties.getClaimBatchSize(), Instant.now());
                for (UUID id : claimed) {
                    processOne(id);
                }
                if (claimed.isEmpty()) {
                    Thread.sleep(properties.getPollIntervalMs());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("outbox_poll_error message={}", ex.getMessage());
                try {
                    Thread.sleep(Math.max(1000L, properties.getPollIntervalMs()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void processOne(UUID id) {
        OutboxEvent event = outboxRepository.findById(id).orElse(null);
        if (event == null) {
            return;
        }
        if ("PROCESSED".equals(event.status())) {
            return;
        }
        try {
            dispatcher.dispatch(event);
            outboxRepository.markProcessed(id);
        } catch (Exception ex) {
            long delaySeconds = backoffSeconds(event.attemptCount());
            outboxRepository.markRetryOrDead(
                    id,
                    ex.getMessage() == null ? "handler failed" : ex.getMessage().substring(
                            0, Math.min(ex.getMessage().length(), 500)),
                    Instant.now().plusSeconds(delaySeconds)
            );
            log.warn(
                    "outbox_handler_failed id={} eventType={} attempt={} delaySec={} message={}",
                    id, event.eventType(), event.attemptCount(), delaySeconds, ex.getMessage()
            );
        }
    }

    /** Backoff: 30s, 2m, 10m, 1h (capped). */
    static long backoffSeconds(int attemptCount) {
        return switch (Math.max(attemptCount, 1)) {
            case 1 -> 30L;
            case 2 -> 120L;
            case 3 -> 600L;
            default -> 3600L;
        };
    }
}
