package com.rsreach.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = PersistenceTestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.persistence.ContactConsentMigrationTest#dockerAvailable")
class ContactConsentMigrationTest {

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
        registry.add("RSREACH_SECRETS_MASTER_KEY", () -> "phase2-m3-test-master-key");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v13AndV14ConsentScopeHardening() {
        Integer flywayV13 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '13' AND success = TRUE",
                Integer.class
        );
        Integer flywayV14 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '14' AND success = TRUE",
                Integer.class
        );
        assertThat(flywayV13).isEqualTo(1);
        assertThat(flywayV14).isEqualTo(1);

        List<String> tables = jdbcTemplate.query(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('contact_consents', 'contact_consent_events', 'contact_suppressions')
                ORDER BY table_name
                """,
                (rs, i) -> rs.getString("table_name")
        );
        assertThat(tables).containsExactly(
                "contact_consent_events", "contact_consents", "contact_suppressions");

        String uniqueDef = jdbcTemplate.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'uq_contact_consent'
                """,
                String.class
        );
        assertThat(uniqueDef).contains("workspace_id");

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
    }
}
