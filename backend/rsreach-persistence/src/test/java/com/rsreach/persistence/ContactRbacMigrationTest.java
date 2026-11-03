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

/**
 * Verifies Flyway V10 seeds contact RBAC: admins manage, members read-only.
 */
@SpringBootTest(classes = PersistenceTestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.persistence.ContactRbacMigrationTest#dockerAvailable")
class ContactRbacMigrationTest {

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
        registry.add("RSREACH_SECRETS_MASTER_KEY", () -> "phase2-m0-test-master-key");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v10SeedsContactPermissionsAndRoleMappings() {
        List<String> permissionCodes = jdbcTemplate.query(
                "SELECT code FROM permissions WHERE code LIKE 'contacts.%' ORDER BY code",
                (rs, i) -> rs.getString("code")
        );
        assertThat(permissionCodes).containsExactly(
                "contacts.consent.manage",
                "contacts.export",
                "contacts.import",
                "contacts.manage",
                "contacts.read"
        );

        assertThat(permissionsForRole("TENANT_OWNER")).containsExactlyInAnyOrderElementsOf(permissionCodes);
        assertThat(permissionsForRole("TENANT_ADMIN")).containsExactlyInAnyOrderElementsOf(permissionCodes);
        assertThat(permissionsForRole("WORKSPACE_ADMIN")).containsExactlyInAnyOrderElementsOf(permissionCodes);
        assertThat(permissionsForRole("WORKSPACE_MEMBER")).containsExactly("contacts.read");

        Integer flywayV10 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10' AND success = TRUE",
                Integer.class
        );
        assertThat(flywayV10).isEqualTo(1);
    }

    private List<String> permissionsForRole(String roleCode) {
        return jdbcTemplate.query(
                """
                SELECT p.code
                FROM roles r
                JOIN role_permissions rp ON rp.role_id = r.id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = ? AND p.code LIKE 'contacts.%'
                ORDER BY p.code
                """,
                (rs, i) -> rs.getString("code"),
                roleCode
        );
    }
}
