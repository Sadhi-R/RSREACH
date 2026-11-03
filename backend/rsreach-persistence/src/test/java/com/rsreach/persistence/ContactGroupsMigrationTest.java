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
@EnabledIf("com.rsreach.persistence.ContactGroupsMigrationTest#dockerAvailable")
class ContactGroupsMigrationTest {

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
        registry.add("RSREACH_SECRETS_MASTER_KEY", () -> "phase2-m2-test-master-key");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v12CreatesGroupTablesAndConstraints() {
        Integer flywayV12 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = TRUE",
                Integer.class
        );
        assertThat(flywayV12).isEqualTo(1);

        List<String> tables = jdbcTemplate.query(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('contact_groups', 'contact_group_members')
                ORDER BY table_name
                """,
                (rs, i) -> rs.getString("table_name")
        );
        assertThat(tables).containsExactly("contact_group_members", "contact_groups");
    }
}
