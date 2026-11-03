package com.rsreach.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = PersistenceTestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.persistence.ContactMergeMigrationTest#dockerAvailable")
class ContactMergeMigrationTest {

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
        registry.add("RSREACH_SECRETS_MASTER_KEY", () -> "phase2-m4-m0-test-master-key");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void freshMigrationAppliesV15AndV16SchemaAndPreservesPriorVersions() {
        for (int version = 1; version <= 16; version++) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = TRUE",
                    Integer.class,
                    String.valueOf(version)
            );
            assertThat(count).as("flyway version %s", version).isEqualTo(1);
        }

        List<String> tables = jdbcTemplate.query(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'contact_duplicate_candidates', 'contact_merges', 'contact_merge_items',
                      'contacts', 'contact_consents', 'contact_consent_events', 'outbox_events'
                  )
                ORDER BY table_name
                """,
                (rs, i) -> rs.getString("table_name")
        );
        assertThat(tables).containsExactly(
                "contact_consent_events",
                "contact_consents",
                "contact_duplicate_candidates",
                "contact_merge_items",
                "contact_merges",
                "contacts",
                "outbox_events"
        );

        Integer mergeColumns = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'contacts'
                  AND column_name IN ('merged_into_contact_id', 'merged_at')
                """,
                Integer.class
        );
        assertThat(mergeColumns).isEqualTo(2);

        Integer requestHash = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'contact_merges' AND column_name = 'request_hash'
                """,
                Integer.class
        );
        assertThat(requestHash).isEqualTo(1);

        Integer appendTriggers = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_trigger
                WHERE tgname IN (
                    'trg_contact_consent_events_no_update',
                    'trg_contact_consent_events_no_delete'
                )
                """,
                Integer.class
        );
        assertThat(appendTriggers).isEqualTo(2);

        Integer mergedIntoScopeFk = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'fk_contacts_merged_into_home_scope'
                """,
                Integer.class
        );
        assertThat(mergedIntoScopeFk)
                .as("V16 home-scope FK for merged_into_contact_id")
                .isEqualTo(1);
    }

    @Test
    void upgradesFromV14ThroughV16() {
        try (PostgreSQLContainer<?> upgradeDb = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("rsreach_upgrade")
                .withUsername("rsreach")
                .withPassword("test")) {
            upgradeDb.start();

            Flyway.configure()
                    .dataSource(upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())
                    .locations("classpath:db/migration")
                    .target("14")
                    .load()
                    .migrate();

            JdbcTemplate upgradeJdbc = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword()
                    )
            );
            Integer v15Before = upgradeJdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '15'",
                    Integer.class
            );
            assertThat(v15Before).isZero();
            Integer candidateBefore = upgradeJdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'contact_duplicate_candidates'
                    """,
                    Integer.class
            );
            assertThat(candidateBefore).isZero();

            Flyway.configure()
                    .dataSource(upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            Integer v15After = upgradeJdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '15' AND success = TRUE",
                    Integer.class
            );
            assertThat(v15After).isEqualTo(1);
            Integer v16After = upgradeJdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success = TRUE",
                    Integer.class
            );
            assertThat(v16After).isEqualTo(1);
            Integer candidateAfter = upgradeJdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'contact_duplicate_candidates'
                    """,
                    Integer.class
            );
            assertThat(candidateAfter).isEqualTo(1);
            Integer scopeFk = upgradeJdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conname = 'fk_contacts_merged_into_home_scope'
                    """,
                    Integer.class
            );
            assertThat(scopeFk).isEqualTo(1);

            for (int version = 1; version <= 14; version++) {
                Integer count = upgradeJdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = TRUE",
                        Integer.class,
                        String.valueOf(version)
                );
                assertThat(count).as("upgrade retained version %s", version).isEqualTo(1);
            }
        }
    }

    @Test
    void candidateAndMergeConstraintsEnforceSameWorkspaceCanonicalPairAndIdempotency() {
        Fixture fx = seedFixture();

        // PostgreSQL UUID ordering differs from Java UUID.compareTo — use DB LEAST/GREATEST.
        UUID low = jdbcTemplate.queryForObject(
                "SELECT LEAST(?::uuid, ?::uuid)", UUID.class, fx.contactA(), fx.contactB());
        UUID high = jdbcTemplate.queryForObject(
                "SELECT GREATEST(?::uuid, ?::uuid)", UUID.class, fx.contactA(), fx.contactB());

        jdbcTemplate.update(
                """
                INSERT INTO contact_duplicate_candidates (
                    id, tenant_id, workspace_id, contact_id_a, contact_id_b,
                    match_types, confidence, score, evidence, status
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), 'HIGH', 80, CAST(? AS jsonb), 'OPEN')
                """,
                UUID.randomUUID(), fx.tenantId(), fx.workspaceA(), low, high,
                "[\"EXACT_EMAIL\"]", "{\"matches\":[]}"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO contact_duplicate_candidates (
                    id, tenant_id, workspace_id, contact_id_a, contact_id_b,
                    match_types, confidence, score, evidence, status
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), 'HIGH', 80, CAST(? AS jsonb), 'OPEN')
                """,
                UUID.randomUUID(), fx.tenantId(), fx.workspaceA(), low, high,
                "[\"EXACT_EMAIL\"]", "{\"matches\":[]}"
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO contact_duplicate_candidates (
                    id, tenant_id, workspace_id, contact_id_a, contact_id_b,
                    match_types, confidence, score, evidence, status
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), 'HIGH', 80, CAST(? AS jsonb), 'OPEN')
                """,
                UUID.randomUUID(), fx.tenantId(), fx.workspaceA(), high, low,
                "[\"EXACT_EMAIL\"]", "{\"matches\":[]}"
        )).isInstanceOf(DataIntegrityViolationException.class);

        UUID crossLow = jdbcTemplate.queryForObject(
                "SELECT LEAST(?::uuid, ?::uuid)", UUID.class, low, fx.contactOtherWs());
        UUID crossHigh = jdbcTemplate.queryForObject(
                "SELECT GREATEST(?::uuid, ?::uuid)", UUID.class, low, fx.contactOtherWs());
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO contact_duplicate_candidates (
                    id, tenant_id, workspace_id, contact_id_a, contact_id_b,
                    match_types, confidence, score, evidence, status
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), 'HIGH', 80, CAST(? AS jsonb), 'OPEN')
                """,
                UUID.randomUUID(), fx.tenantId(), fx.workspaceA(), crossLow, crossHigh,
                "[\"EXACT_EMAIL\"]", "{\"matches\":[]}"
        )).isInstanceOf(DataIntegrityViolationException.class);

        UUID mergeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_merges (
                    id, tenant_id, workspace_id, survivor_contact_id, loser_contact_id,
                    status, idempotency_key, request_hash, preview_hash, actor_id
                ) VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?)
                """,
                mergeId, fx.tenantId(), fx.workspaceA(), high, low,
                "idem-1", "hash-aaa", "preview-aaa", fx.userId()
        );

        jdbcTemplate.update(
                """
                INSERT INTO contact_merge_items (id, merge_id, item_type, item_key, action)
                VALUES (?, ?, 'FIELD', 'display_name', 'KEPT_SURVIVOR')
                """,
                UUID.randomUUID(), mergeId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO contact_merges (
                    id, tenant_id, workspace_id, survivor_contact_id, loser_contact_id,
                    status, idempotency_key, request_hash, preview_hash, actor_id
                ) VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?)
                """,
                UUID.randomUUID(), fx.tenantId(), fx.workspaceA(), high, low,
                "idem-1", "hash-bbb", "preview-bbb", fx.userId()
        )).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(
                """
                UPDATE contacts
                SET merged_into_contact_id = ?, merged_at = NOW(), status = 'ARCHIVED', archived_at = NOW()
                WHERE id = ?
                """,
                high, low
        );
        Integer marked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contacts WHERE id = ? AND merged_into_contact_id = ?",
                Integer.class, low, high
        );
        assertThat(marked).isEqualTo(1);

        // V16: merged_into cannot point at a contact in another home workspace.
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE contacts
                SET merged_into_contact_id = ?, merged_at = NOW(), status = 'ARCHIVED', archived_at = NOW()
                WHERE id = ?
                """,
                fx.contactOtherWs(), fx.contactB()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void consentEventAcceptsMergeReconciledAndRemainsAppendOnly() {
        Fixture fx = seedFixture();
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_consent_events (
                    id, tenant_id, workspace_id, contact_id, channel_type, purpose,
                    previous_status, new_status, event_type, source, evidence, actor_id
                ) VALUES (?, ?, ?, ?, 'EMAIL', 'MARKETING', 'OPTED_IN', 'UNKNOWN',
                          'MERGE_RECONCILED', 'API', CAST(? AS jsonb), ?)
                """,
                eventId, fx.tenantId(), fx.workspaceA(), fx.contactA(),
                "{\"mergeId\":\"00000000-0000-0000-0000-000000000001\"}", fx.userId()
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE contact_consent_events SET new_status = 'OPTED_IN' WHERE id = ?", eventId
        )).hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM contact_consent_events WHERE id = ?", eventId
        )).hasMessageContaining("append-only");
    }

    private Fixture seedFixture() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID workspaceA = UUID.randomUUID();
        UUID workspaceB = UUID.randomUUID();
        UUID contactA = UUID.randomUUID();
        UUID contactB = UUID.randomUUID();
        UUID contactOtherWs = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO customer_users (id, email, email_normalized, display_name, status)
                VALUES (?, ?, ?, 'Merge Fixture', 'ACTIVE')
                """,
                userId, "merge-m40-" + userId + "@example.com", "merge-m40-" + userId + "@example.com"
        );
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, owner_user_id)
                VALUES (?, 'Merge Tenant', ?, 'ACTIVE', ?)
                """,
                tenantId, "merge-tenant-" + tenantId, userId
        );
        jdbcTemplate.update(
                """
                INSERT INTO workspaces (id, tenant_id, name, slug, status)
                VALUES (?, ?, 'Workspace A', 'ws-a', 'ACTIVE')
                """,
                workspaceA, tenantId
        );
        jdbcTemplate.update(
                """
                INSERT INTO workspaces (id, tenant_id, name, slug, status)
                VALUES (?, ?, 'Workspace B', 'ws-b', 'ACTIVE')
                """,
                workspaceB, tenantId
        );
        jdbcTemplate.update(
                """
                INSERT INTO contacts (
                    id, tenant_id, home_workspace_id, display_name, status, created_by
                ) VALUES (?, ?, ?, 'Contact A', 'ACTIVE', ?)
                """,
                contactA, tenantId, workspaceA, userId
        );
        jdbcTemplate.update(
                """
                INSERT INTO contacts (
                    id, tenant_id, home_workspace_id, display_name, status, created_by
                ) VALUES (?, ?, ?, 'Contact B', 'ACTIVE', ?)
                """,
                contactB, tenantId, workspaceA, userId
        );
        jdbcTemplate.update(
                """
                INSERT INTO contacts (
                    id, tenant_id, home_workspace_id, display_name, status, created_by
                ) VALUES (?, ?, ?, 'Contact Other WS', 'ACTIVE', ?)
                """,
                contactOtherWs, tenantId, workspaceB, userId
        );
        return new Fixture(userId, tenantId, workspaceA, workspaceB, contactA, contactB, contactOtherWs);
    }

    private record Fixture(
            UUID userId,
            UUID tenantId,
            UUID workspaceA,
            UUID workspaceB,
            UUID contactA,
            UUID contactB,
            UUID contactOtherWs
    ) {
    }
}
