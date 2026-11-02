package com.rsreach.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import com.rsreach.persistence.secrets.EncryptedSecret;
import com.rsreach.persistence.secrets.IntegrationSecretRepository;
import com.rsreach.persistence.secrets.SecretEncryptionService;
import com.rsreach.persistence.webhook.ProviderWebhookRepository;
import com.rsreach.persistence.webhook.WebhookIngestCommand;
import com.rsreach.persistence.webhook.WebhookIngestResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = PersistenceTestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.persistence.Phase0ReliabilityTest#dockerAvailable")
class Phase0ReliabilityTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("rsreach")
            .withUsername("rsreach")
            .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("RSREACH_SECRETS_MASTER_KEY", () -> "phase0-test-master-key-v1");
        registry.add("RSREACH_SECRETS_MASTER_KEY_V2", () -> "phase0-test-master-key-v2-rotated");
    }

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ProviderWebhookRepository webhookRepository;

    @Autowired
    private SecretEncryptionService secretEncryptionService;

    @Autowired
    private IntegrationSecretRepository integrationSecretRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void failedBusinessTransactionLeavesNoOrphanOutboxEvent() {
        long before = outboxRepository.countAll();
        UUID aggregateId = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    INSERT INTO platform_metadata(key, value, updated_at)
                    VALUES (?, ?, NOW())
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
                    """,
                    "test.rollback." + aggregateId,
                    "written"
            );
            outboxRepository.append(OutboxAppendCommand.of(
                    "platform_metadata",
                    aggregateId,
                    "METADATA_UPSERTED",
                    "{\"ok\":true}",
                    null,
                    null,
                    "corr-1",
                    "idem-" + aggregateId
            ));
            throw new IllegalStateException("force rollback");
        }));

        assertEquals(before, outboxRepository.countAll());
        Integer metadataCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_metadata WHERE key = ?",
                Integer.class,
                "test.rollback." + aggregateId
        );
        assertEquals(0, metadataCount);
    }

    @Test
    void outboxClaimSupportsRetryThenDeadLetter() {
        UUID id = transactionTemplate.execute(status -> outboxRepository.append(
                new OutboxAppendCommand(
                        "demo",
                        UUID.randomUUID(),
                        "DEMO_EVENT",
                        "{\"n\":1}",
                        UUID.randomUUID(),
                        null,
                        "corr-retry",
                        "idem-retry-" + UUID.randomUUID(),
                        2
                )
        ));

        List<UUID> firstClaim = outboxRepository.claimBatch("worker-a", 10, Instant.now());
        assertTrue(firstClaim.contains(id));
        outboxRepository.markRetryOrDead(id, "temporary", Instant.now().minusSeconds(1));

        List<UUID> secondClaim = outboxRepository.claimBatch("worker-b", 10, Instant.now());
        assertTrue(secondClaim.contains(id));
        outboxRepository.markRetryOrDead(id, "still failing", Instant.now().plusSeconds(30));

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?",
                String.class,
                id
        );
        assertEquals("DEAD", status);
    }

    @Test
    void webhookUniquenessIsScopedToProviderAndConnectedAccount() {
        WebhookIngestCommand accountA = new WebhookIngestCommand(
                "meta",
                "waba-A",
                "evt-1",
                "message.status",
                Instant.now(),
                UUID.randomUUID(),
                null,
                true,
                "{\"status\":\"delivered\"}",
                "hash-1"
        );
        WebhookIngestCommand accountB = new WebhookIngestCommand(
                "meta",
                "waba-B",
                "evt-1",
                "message.status",
                Instant.now(),
                UUID.randomUUID(),
                null,
                true,
                "{\"status\":\"delivered\"}",
                "hash-1"
        );

        WebhookIngestResult first = webhookRepository.ingest(accountA);
        WebhookIngestResult duplicate = webhookRepository.ingest(accountA);
        WebhookIngestResult otherAccount = webhookRepository.ingest(accountB);

        assertTrue(first.inserted());
        assertFalse(duplicate.inserted());
        assertEquals(first.eventId(), duplicate.eventId());
        assertTrue(otherAccount.inserted());
        assertNotEquals(first.eventId(), otherAccount.eventId());
        assertEquals(2, webhookRepository.countAll());

        webhookRepository.markQueued(first.eventId());
        webhookRepository.markProcessed(first.eventId());
        assertEquals("PROCESSED", webhookRepository.statusOf(first.eventId()));
        // Duplicate ingest must not reopen processed events.
        WebhookIngestResult afterProcessed = webhookRepository.ingest(accountA);
        assertFalse(afterProcessed.inserted());
        assertEquals("PROCESSED", webhookRepository.statusOf(first.eventId()));
    }

    @Test
    void secretsRemainDecryptableAfterKeyRotation() {
        String alias = "rsreach-master";
        integrationSecretRepository.upsertKeyVersion(alias, 1, "ACTIVE");

        byte[] plaintext = "oauth-refresh-token".getBytes(StandardCharsets.UTF_8);
        EncryptedSecret encryptedV1 = secretEncryptionService.encrypt(alias, 1, plaintext);
        UUID tenantId = UUID.randomUUID();
        integrationSecretRepository.saveSecret(tenantId, null, "whatsapp.token", encryptedV1);

        integrationSecretRepository.upsertKeyVersion(alias, 1, "RETIRED");
        integrationSecretRepository.upsertKeyVersion(alias, 2, "ACTIVE");

        EncryptedSecret stored = integrationSecretRepository.findSecret(tenantId, null, "whatsapp.token")
                .orElseThrow();
        assertEquals(1, stored.keyVersion());
        byte[] decrypted = secretEncryptionService.decrypt(stored);
        assertEquals("oauth-refresh-token", new String(decrypted, StandardCharsets.UTF_8));

        EncryptedSecret encryptedV2 = secretEncryptionService.encrypt(alias, 2, plaintext);
        integrationSecretRepository.saveSecret(tenantId, null, "whatsapp.token", encryptedV2);
        EncryptedSecret rotated = integrationSecretRepository.findSecret(tenantId, null, "whatsapp.token")
                .orElseThrow();
        assertEquals(2, rotated.keyVersion());
        assertEquals(
                "oauth-refresh-token",
                new String(secretEncryptionService.decrypt(rotated), StandardCharsets.UTF_8)
        );
    }
}
