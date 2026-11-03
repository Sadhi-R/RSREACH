package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.contacts.ContactMergeService;
import com.rsreach.contacts.MergeExecutionProbe;
import com.rsreach.contacts.MergeModels;
import com.rsreach.contacts.MergePreviewService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M4.2.1 — real PostgreSQL concurrency and atomicity for contact merge.
 * Concurrent calls go through {@link ContactMergeService} (independent Spring transactions),
 * not sequential service invokes and not MockMvc worker threads.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=local",
        "rsreach.security.expose-dev-tokens=false",
        "rsreach.jwt.customer.secret=test-customer-jwt-secret-value-32b",
        "rsreach.jwt.staff.secret=test-staff-jwt-secret-value-32b!!!",
        "rsreach.secrets.master-key=test-master-key-material",
        "rsreach.bootstrap.staff-email=owner@contact-m421.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!",
        "spring.datasource.hikari.maximum-pool-size=12"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM4MergeConcurrencyAtomicityTest#dockerAvailable")
@Import(Phase2ContactM4MergeConcurrencyAtomicityTest.ProbeConfig.class)
class Phase2ContactM4MergeConcurrencyAtomicityTest {

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
    }

    @TestConfiguration
    static class ProbeConfig {
        static final AtomicInteger failBeforePersist = new AtomicInteger(0);

        @Bean
        MergeExecutionProbe mergeExecutionProbe() {
            return () -> {
                if (failBeforePersist.getAndDecrement() > 0) {
                    throw new IllegalStateException("test-forced merge rollback before persist");
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ContactMergeService contactMergeService;
    @Autowired private MergePreviewService mergePreviewService;

    private String ownerAccess;
    private UUID ownerId;
    private TenantPair tenant;

    @BeforeEach
    void resetProbe() {
        ProbeConfig.failBeforePersist.set(0);
    }

    @RepeatedTest(3)
    void concurrentSameIdempotencyKeyProducesOneMerge() throws Exception {
        ensureOwner();
        Fixture fx = prepareMergeFixture("idem-" + UUID.randomUUID().toString().substring(0, 8));
        String idemKey = "m421-idem-" + UUID.randomUUID();
        MergeModels.ExecuteCommand command = executeCommand(fx);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MergeModels.MergeView>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start barrier timeout");
                }
                return contactMergeService.execute(
                        fx.tenantId(), fx.workspaceId(), ownerId, idemKey, command);
            }));
        }
        assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        MergeModels.MergeView a = futures.get(0).get(45, TimeUnit.SECONDS);
        MergeModels.MergeView b = futures.get(1).get(45, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(a.id()).isEqualTo(b.id());
        assertThat(a.survivorContactId()).isEqualTo(fx.survivorId());
        assertThat(a.loserContactId()).isEqualTo(fx.loserId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE tenant_id = ? AND idempotency_key = ?",
                Integer.class, fx.tenantId(), idemKey
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE loser_contact_id = ? AND status = 'COMPLETED'",
                Integer.class, fx.loserId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merge_items WHERE merge_id = ?",
                Integer.class, a.id()
        )).isEqualTo(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_merge_items i
                JOIN contact_merges m ON m.id = i.merge_id
                WHERE m.loser_contact_id = ?
                """,
                Integer.class, fx.loserId()
        ));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'contact.merged' AND resource_id = ?",
                Integer.class, a.id().toString()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consent_events
                WHERE contact_id = ? AND event_type = 'MERGE_RECONCILED'
                """,
                Integer.class, fx.survivorId()
        )).isEqualTo(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consent_events
                WHERE contact_id = ? AND event_type = 'MERGE_RECONCILED'
                """,
                Integer.class, fx.survivorId()
        ));
        assertSingleLoserMergeSideEffects(fx, a.id());
    }

    @RepeatedTest(3)
    void concurrentDifferentKeysSameLoserAllowOnlyOneMerge() throws Exception {
        ensureOwner();
        Fixture fx = prepareMergeFixture("loser-" + UUID.randomUUID().toString().substring(0, 8));
        MergeModels.ExecuteCommand command = executeCommand(fx);
        String key1 = "m421-loser-a-" + UUID.randomUUID();
        String key2 = "m421-loser-b-" + UUID.randomUUID();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicReference<UUID> winnerId = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> runMerge(ready, start, fx, key1, command, success, conflict, winnerId)));
        futures.add(pool.submit(() -> runMerge(ready, start, fx, key2, command, success, conflict, winnerId)));
        assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(45, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE loser_contact_id = ? AND status = 'COMPLETED'",
                Integer.class, fx.loserId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT merged_into_contact_id FROM contacts WHERE id = ?",
                UUID.class, fx.loserId()
        )).isEqualTo(fx.survivorId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'contact.merged' AND resource_id = ?",
                Integer.class, winnerId.get().toString()
        )).isEqualTo(1);
        assertSingleLoserMergeSideEffects(fx, winnerId.get());
    }

    @Test
    void forcedFailureRollsBackAllMergeSideEffects() throws Exception {
        ensureOwner();
        Fixture fx = prepareMergeFixture("rb-" + UUID.randomUUID().toString().substring(0, 8));
        Snapshot before = snapshot(fx);

        ProbeConfig.failBeforePersist.set(1);
        MergeModels.ExecuteCommand command = executeCommand(fx);
        assertThatThrownBy(() -> contactMergeService.execute(
                fx.tenantId(), fx.workspaceId(), ownerId, "m421-rollback-" + UUID.randomUUID(), command
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-forced merge rollback");

        Snapshot after = snapshot(fx);
        assertThat(after).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE loser_contact_id = ?",
                Integer.class, fx.loserId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merge_items i JOIN contact_merges m ON m.id = i.merge_id WHERE m.loser_contact_id = ?",
                Integer.class, fx.loserId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action IN ('contact.merged','contact.share_revoked') AND details_json::text LIKE ?",
                Integer.class, "%" + fx.loserId() + "%"
        )).isZero();

        // Retry after rolled-back attempt succeeds with a fresh key.
        ProbeConfig.failBeforePersist.set(0);
        MergeModels.MergeView merged = contactMergeService.execute(
                fx.tenantId(), fx.workspaceId(), ownerId, "m421-retry-" + UUID.randomUUID(), command);
        assertThat(merged.status()).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE loser_contact_id = ?",
                Integer.class, fx.loserId()
        )).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentRequestReturnsConflictAndFailedTxLeavesNoCompletedRecord() throws Exception {
        ensureOwner();
        Fixture fx = prepareMergeFixture("race-" + UUID.randomUUID().toString().substring(0, 8));
        MergeModels.ExecuteCommand command = executeCommand(fx);
        String key = "m421-diff-" + UUID.randomUUID();

        MergeModels.MergeView first = contactMergeService.execute(
                fx.tenantId(), fx.workspaceId(), ownerId, key, command);
        assertThat(first.id()).isNotNull();

        MergeModels.ExecuteCommand swapped = new MergeModels.ExecuteCommand(
                command.candidateId(),
                command.loserContactId(),
                command.survivorContactId(),
                command.fieldChoices(),
                command.primaryChoices(),
                command.previewHash(),
                true
        );
        assertThatThrownBy(() -> contactMergeService.execute(
                fx.tenantId(), fx.workspaceId(), ownerId, key, swapped
        )).isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE tenant_id = ? AND idempotency_key = ?",
                Integer.class, fx.tenantId(), key
        )).isEqualTo(1);

        // Forced failure leaves no completed idempotency row for a new key.
        Fixture fx2 = prepareMergeFixture("failkey-" + UUID.randomUUID().toString().substring(0, 8));
        String failKey = "m421-failkey-" + UUID.randomUUID();
        ProbeConfig.failBeforePersist.set(1);
        assertThatThrownBy(() -> contactMergeService.execute(
                fx2.tenantId(), fx2.workspaceId(), ownerId, failKey, executeCommand(fx2)
        )).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE tenant_id = ? AND idempotency_key = ?",
                Integer.class, fx2.tenantId(), failKey
        )).isZero();
    }

    private void runMerge(
            CountDownLatch ready,
            CountDownLatch start,
            Fixture fx,
            String key,
            MergeModels.ExecuteCommand command,
            AtomicInteger success,
            AtomicInteger conflict,
            AtomicReference<UUID> winnerId
    ) {
        ready.countDown();
        try {
            if (!start.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start barrier timeout");
            }
            MergeModels.MergeView view = contactMergeService.execute(
                    fx.tenantId(), fx.workspaceId(), ownerId, key, command);
            success.incrementAndGet();
            winnerId.compareAndSet(null, view.id());
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCode.CONFLICT || ex.getCode() == ErrorCode.NOT_FOUND) {
                conflict.incrementAndGet();
            } else {
                throw ex;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for start barrier", ex);
        }
    }

    private void assertSingleLoserMergeSideEffects(Fixture fx, UUID mergeId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resource_shares WHERE id = ? AND revoked_at IS NOT NULL",
                Integer.class, fx.shareId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_group_members
                WHERE group_id = ? AND contact_id = ? AND source = 'MANUAL'
                """,
                Integer.class, fx.staticGroupId(), fx.survivorId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_group_members WHERE group_id = ? AND contact_id = ?",
                Integer.class, fx.staticGroupId(), fx.loserId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM outbox_events
                WHERE event_type = 'CONTACT_GROUP_REFRESH' AND aggregate_id = ?
                """,
                Integer.class, fx.dynamicGroupId()
        )).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merge_items WHERE merge_id = ?",
                Integer.class, mergeId
        )).isPositive();
    }

    private Snapshot snapshot(Fixture fx) {
        Integer channels = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_channels
                WHERE contact_id IN (?, ?) AND status = 'ACTIVE'
                """,
                Integer.class, fx.survivorId(), fx.loserId()
        );
        String consent = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(string_agg(contact_id::text || ':' || channel_type || ':' || purpose || ':' || status, ','
                    ORDER BY contact_id, channel_type, purpose), '')
                FROM contact_consents
                WHERE contact_id IN (?, ?)
                """,
                String.class, fx.survivorId(), fx.loserId()
        );
        Integer consentEvents = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consent_events
                WHERE contact_id IN (?, ?) AND event_type = 'MERGE_RECONCILED'
                """,
                Integer.class, fx.survivorId(), fx.loserId()
        );
        Integer staticMembers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_group_members WHERE group_id = ?",
                Integer.class, fx.staticGroupId()
        );
        Integer pendingDyn = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_groups
                WHERE id = ? AND materialization_status = 'PENDING'
                """,
                Integer.class, fx.dynamicGroupId()
        );
        Integer outbox = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_id = ? AND event_type = 'CONTACT_GROUP_REFRESH'
                """,
                Integer.class, fx.dynamicGroupId()
        );
        Integer sharesActive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resource_shares WHERE id = ? AND revoked_at IS NULL",
                Integer.class, fx.shareId()
        );
        Integer merges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE loser_contact_id = ?",
                Integer.class, fx.loserId()
        );
        String candidateStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM contact_duplicate_candidates WHERE id = ?",
                String.class, fx.candidateId()
        );
        UUID mergedInto = jdbcTemplate.queryForObject(
                "SELECT merged_into_contact_id FROM contacts WHERE id = ?",
                UUID.class, fx.loserId()
        );
        return new Snapshot(
                channels, consent, consentEvents, staticMembers, pendingDyn, outbox,
                sharesActive, merges, candidateStatus, mergedInto
        );
    }

    private MergeModels.ExecuteCommand executeCommand(Fixture fx) {
        MergeModels.MergePreview preview = mergePreviewService.preview(
                fx.tenantId(),
                fx.workspaceId(),
                new MergeModels.PreviewCommand(
                        fx.candidateId(), fx.survivorId(), fx.loserId(), List.of(), List.of())
        );
        assertThat(preview.mergeAllowed()).isTrue();
        return new MergeModels.ExecuteCommand(
                fx.candidateId(),
                fx.survivorId(),
                fx.loserId(),
                List.of(),
                List.of(),
                preview.previewHash(),
                true
        );
    }

    private Fixture prepareMergeFixture(String tag) throws Exception {
        String loserId = createContact(ownerAccess, tenant, "Loser " + tag, tag + ".lose@example.com", phoneFor(tag, 1));
        grantConsent(ownerAccess, tenant, loserId, "EMAIL", "MARKETING", "OPTED_IN");
        grantConsent(ownerAccess, tenant, loserId, "WHATSAPP", "MARKETING", "OPTED_IN");
        String staticGroupId = createStaticGroup(ownerAccess, tenant, "sg-" + tag);
        mockMvc.perform(post("/api/v1/app/contact-groups/" + staticGroupId + "/members")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("contactIds", List.of(loserId)))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/app/contacts/" + loserId + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isOk());

        String survivorId = createContact(ownerAccess, tenant, "Survivor " + tag, tag + ".lose@example.com", phoneFor(tag, 2));
        grantConsent(ownerAccess, tenant, survivorId, "EMAIL", "MARKETING", "OPTED_IN");
        grantConsent(ownerAccess, tenant, survivorId, "WHATSAPP", "MARKETING", "OPTED_IN");

        UUID movePhone = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_channels (
                    id, tenant_id, workspace_id, contact_id, channel_type, value_raw, value_normalized,
                    is_primary, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PHONE', ?, ?, FALSE, 'ACTIVE', NOW(), NOW())
                """,
                movePhone, UUID.fromString(tenant.tenantId()), UUID.fromString(tenant.workspaceId()),
                UUID.fromString(loserId), phoneFor(tag, 9), phoneFor(tag, 9)
        );
        jdbcTemplate.update(
                """
                INSERT INTO contact_channels (
                    id, tenant_id, workspace_id, contact_id, channel_type, value_raw, value_normalized,
                    is_primary, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'EMAIL', ?, ?, FALSE, 'ARCHIVED', NOW(), NOW())
                """,
                UUID.randomUUID(), UUID.fromString(tenant.tenantId()), UUID.fromString(tenant.workspaceId()),
                UUID.fromString(loserId), "old." + tag + "@example.com", "old." + tag + "@example.com"
        );
        jdbcTemplate.update(
                """
                INSERT INTO contact_channels (
                    id, tenant_id, workspace_id, contact_id, channel_type, value_raw, value_normalized,
                    is_primary, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'EMAIL', ?, ?, FALSE, 'INVALID', NOW(), NOW())
                """,
                UUID.randomUUID(), UUID.fromString(tenant.tenantId()), UUID.fromString(tenant.workspaceId()),
                UUID.fromString(loserId), "bad." + tag + "@example.com", "bad." + tag + "@example.com"
        );

        String dynamicGroupId = createDynamicGroup(ownerAccess, tenant, "dg-" + tag);
        MvcResult wsB = mockMvc.perform(post("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Share " + tag + "\",\"slug\":\"share-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String targetWs = objectMapper.readTree(wsB.getResponse().getContentAsString()).get("id").asText();
        UUID shareId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO resource_shares (
                    id, tenant_id, source_workspace_id, target_workspace_id,
                    resource_type, resource_id, permission, created_by, created_at
                ) VALUES (?, ?, ?, ?, 'CONTACT', ?, 'READ', ?, NOW())
                """,
                shareId, UUID.fromString(tenant.tenantId()), UUID.fromString(tenant.workspaceId()),
                UUID.fromString(targetWs), UUID.fromString(loserId), ownerId
        );

        String candidateId = objectMapper.readTree(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/app/contact-duplicates")
                                .header("Authorization", "Bearer " + ownerAccess)
                                .header("X-Tenant-Id", tenant.tenantId())
                                .header("X-Workspace-Id", tenant.workspaceId())
                                .param("contactId", survivorId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("items").get(0).get("id").asText();

        return new Fixture(
                UUID.fromString(tenant.tenantId()),
                UUID.fromString(tenant.workspaceId()),
                UUID.fromString(candidateId),
                UUID.fromString(survivorId),
                UUID.fromString(loserId),
                UUID.fromString(staticGroupId),
                UUID.fromString(dynamicGroupId),
                shareId
        );
    }

    private void ensureOwner() throws Exception {
        String email = "owner-m421-" + UUID.randomUUID() + "@example.com";
        JsonNode tokens = registerCustomer(email, "Owner M421", "password123");
        ownerAccess = tokens.get("accessToken").asText();
        ownerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = ?", UUID.class, email.toLowerCase());
        tenant = createTenant(ownerAccess, "MergeConc-" + UUID.randomUUID());
    }

    private static String phoneFor(String tag, int n) {
        int base = Math.floorMod(tag.hashCode(), 10_000_000);
        return "+9199" + String.format("%08d", Math.floorMod(base + n, 100_000_000));
    }

    private void grantConsent(String access, TenantPair t, String contactId, String channel, String purpose, String status)
            throws Exception {
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", t.tenantId())
                        .header("X-Workspace-Id", t.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channelType", channel,
                                "purpose", purpose,
                                "status", status,
                                "source", "API",
                                "evidenceNote", "test"
                        ))))
                .andExpect(status().isOk());
    }

    private String createStaticGroup(String access, TenantPair t, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", t.tenantId())
                        .header("X-Workspace-Id", t.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug,
                                "groupType", "STATIC"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createDynamicGroup(String access, TenantPair t, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", t.tenantId())
                        .header("X-Workspace-Id", t.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug,
                                "groupType", "DYNAMIC",
                                "filterDefinition", Map.of(
                                        "version", 1,
                                        "match", "ALL",
                                        "conditions", List.of(Map.of("field", "status", "op", "eq", "value", "ACTIVE"))
                                )
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createContact(String access, TenantPair t, String name, String email, String phone)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", t.tenantId())
                        .header("X-Workspace-Id", t.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", name,
                                "channels", List.of(
                                        Map.of("channelType", "EMAIL", "value", email, "primary", true),
                                        Map.of("channelType", "PHONE", "value", phone, "primary", true)
                                ),
                                "customFields", Map.of()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private TenantPair createTenant(String access, String name) throws Exception {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        MvcResult result = mockMvc.perform(post("/api/v1/app/tenants")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tenantNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TenantPair(tenantNode.get("id").asText(), tenantNode.get("defaultWorkspaceId").asText());
    }

    private JsonNode registerCustomer(String email, String name, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"%s","password":"%s"}
                                """.formatted(email, name, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record TenantPair(String tenantId, String workspaceId) {
    }

    private record Fixture(
            UUID tenantId,
            UUID workspaceId,
            UUID candidateId,
            UUID survivorId,
            UUID loserId,
            UUID staticGroupId,
            UUID dynamicGroupId,
            UUID shareId
    ) {
    }

    private record Snapshot(
            Integer activeChannels,
            String consentProjection,
            Integer mergeReconciledEvents,
            Integer staticMembers,
            Integer dynamicPending,
            Integer refreshOutbox,
            Integer activeShares,
            Integer merges,
            String candidateStatus,
            UUID loserMergedInto
    ) {
    }
}
