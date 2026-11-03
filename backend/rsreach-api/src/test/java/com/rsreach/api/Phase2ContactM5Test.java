package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.contacts.ContactExportService;
import com.rsreach.contacts.ContactImportService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.profiles.active=local",
        "rsreach.security.expose-dev-tokens=false",
        "rsreach.jwt.customer.secret=test-customer-jwt-secret-value-32b",
        "rsreach.jwt.staff.secret=test-staff-jwt-secret-value-32b!!!",
        "rsreach.secrets.master-key=test-master-key-material",
        "rsreach.bootstrap.staff-email=owner@contact-m5.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!",
        "rsreach.contacts.export.sync-max-rows=2"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM5Test#dockerAvailable")
class Phase2ContactM5Test {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ContactImportService contactImportService;
    @Autowired private ContactExportService contactExportService;

    @Test
    void importPreviewCommitSkipExportAuthzIsolationAndNoConsentInference() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m5@example.com", "Owner M5", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenantA = createTenant(ownerAccess, "ImportA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(ownerAccess, "ImportB-" + UUID.randomUUID());

        // Seed existing contact for SKIP conflict
        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "Existing",
                                "channels", java.util.List.of(Map.of(
                                        "channelType", "EMAIL",
                                        "value", "existing@example.com",
                                        "primary", true
                                ))
                        ))))
                .andExpect(status().isCreated());

        String csv = """
                display_name,email,phone
                Alice Import,alice-m5@example.com,+15550001111
                Existing Person,existing@example.com,+15550002222
                Bad Row,not-an-email,+15550003333
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "contacts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile mapping = new MockMultipartFile(
                "mapping", "", "application/json",
                "{\"displayName\":\"display_name\",\"email\":\"email\",\"phone\":\"phone\"}"
                        .getBytes(StandardCharsets.UTF_8));

        MvcResult previewResult = mockMvc.perform(multipart("/api/v1/app/contacts/import/preview")
                        .file(file)
                        .file(mapping)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREVIEW_READY"))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andReturn();
        String jobId = objectMapper.readTree(previewResult.getResponse().getContentAsString()).get("id").asText();

        String idemKey = "import-m5-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/app/contacts/import/" + jobId + "/commit")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conflictPolicy\":\"SKIP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(post("/api/v1/app/contacts/import/" + jobId + "/commit")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conflictPolicy\":\"SKIP\"}"))
                .andExpect(status().isOk());

        contactImportService.processJob(
                UUID.fromString(tenantA.tenantId()),
                UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(jobId));
        contactImportService.processJob(
                UUID.fromString(tenantA.tenantId()),
                UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(jobId));

        mockMvc.perform(get("/api/v1/app/contacts/import/" + jobId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1));

        Integer aliceCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contacts
                WHERE tenant_id = ? AND home_workspace_id = ?
                  AND primary_email_normalized = 'alice-m5@example.com'
                """,
                Integer.class, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId())
        );
        assertThat(aliceCount).isEqualTo(1);

        Integer consentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consents c
                JOIN contacts ct ON ct.id = c.contact_id
                WHERE ct.tenant_id = ? AND ct.home_workspace_id = ?
                  AND ct.primary_email_normalized = 'alice-m5@example.com'
                """,
                Integer.class, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId())
        );
        assertThat(consentCount).isZero();

        // Isolation: tenant B cannot read tenant A job
        mockMvc.perform(get("/api/v1/app/contacts/import/" + jobId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .header("X-Workspace-Id", tenantB.workspaceId()))
                .andExpect(status().isNotFound());

        // Authz: member without import permission
        JsonNode memberTokens = registerCustomer("member-m5@example.com", "Member M5", "password123");
        String memberAccess = memberTokens.get("accessToken").asText();
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'member-m5@example.com'", UUID.class);
        UUID tenantAdminRole = jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = 'TENANT_ADMIN'", UUID.class);
        UUID memberRole = jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = 'WORKSPACE_MEMBER'", UUID.class);
        jdbcTemplate.update(
                """
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), UUID.fromString(tenantA.tenantId()), memberId, tenantAdminRole
        );
        jdbcTemplate.update(
                """
                INSERT INTO workspace_memberships
                    (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                memberId, memberRole
        );
        UUID viewerRoleId = seedTenantViewerRole();
        jdbcTemplate.update(
                "UPDATE tenant_memberships SET role_id = ? WHERE tenant_id = ? AND user_id = ?",
                viewerRoleId, UUID.fromString(tenantA.tenantId()), memberId
        );

        mockMvc.perform(multipart("/api/v1/app/contacts/import/preview")
                        .file(new MockMultipartFile(
                                "file", "x.csv", "text/csv",
                                "display_name,email\nX,x@example.com\n".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isForbidden());

        MvcResult exportResult = mockMvc.perform(get("/api/v1/app/contacts/export")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andReturn();
        String exportCsv = exportResult.getResponse().getContentAsString();
        assertThat(exportCsv).contains("alice-m5@example.com");
        assertThat(exportCsv).contains("consent_email");
        assertThat(exportCsv).doesNotContain("=CMD");

        mockMvc.perform(get("/api/v1/app/contacts/export")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void importSurfacesSuppressionWarningAndAppliesConsentWhenMapped() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m5b@example.com", "Owner M5B", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenant = createTenant(ownerAccess, "ImportSupp-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/app/suppressions")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","value":"suppressed-m5@example.com","reason":"test block"}
                                """))
                .andExpect(status().isCreated());

        String csv = """
                display_name,email,phone,consent_email
                Suppressed Person,suppressed-m5@example.com,+15550009999,OPTED_OUT
                Fresh Person,fresh-m5@example.com,+15550008888,OPTED_IN
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "consent.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile mapping = new MockMultipartFile(
                "mapping", "", "application/json",
                """
                {"displayName":"display_name","email":"email","phone":"phone","consentEmailMarketing":"consent_email"}
                """.getBytes(StandardCharsets.UTF_8));

        MvcResult preview = mockMvc.perform(multipart("/api/v1/app/contacts/import/preview")
                        .file(file)
                        .file(mapping)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode previewBody = objectMapper.readTree(preview.getResponse().getContentAsString());
        assertThat(previewBody.get("sampleRows").toString()).contains("SUPPRESSED");
        String jobId = previewBody.get("id").asText();

        mockMvc.perform(post("/api/v1/app/contacts/import/" + jobId + "/commit")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .header("Idempotency-Key", "consent-import-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conflictPolicy\":\"SKIP\"}"))
                .andExpect(status().isOk());

        contactImportService.processJob(
                UUID.fromString(tenant.tenantId()),
                UUID.fromString(tenant.workspaceId()),
                UUID.fromString(jobId));

        Integer optedIn = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consents c
                JOIN contacts ct ON ct.id = c.contact_id
                WHERE ct.primary_email_normalized = 'fresh-m5@example.com'
                  AND c.channel_type = 'EMAIL' AND c.purpose = 'MARKETING' AND c.status = 'OPTED_IN'
                """,
                Integer.class
        );
        assertThat(optedIn).isEqualTo(1);

        // Consent mapping without permission is rejected
        JsonNode memberTokens = registerCustomer("member-m5b@example.com", "Member M5B", "password123");
        String memberAccess = memberTokens.get("accessToken").asText();
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'member-m5b@example.com'", UUID.class);
        UUID tenantAdminRole = jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = 'TENANT_ADMIN'", UUID.class);
        UUID importOnlyRole = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name, scope, description) VALUES (?, ?, 'ImportOnly', 'WORKSPACE', 'Test')",
                importOnlyRole, "WS_IMPORT_M5_" + importOnlyRole.toString().substring(0, 8)
        );
        jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, p.id FROM permissions p WHERE p.code IN ('contacts.import', 'contacts.read')
                """,
                importOnlyRole
        );
        jdbcTemplate.update(
                """
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), UUID.fromString(tenant.tenantId()), memberId, tenantAdminRole
        );
        jdbcTemplate.update(
                """
                INSERT INTO workspace_memberships
                    (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), UUID.fromString(tenant.tenantId()), UUID.fromString(tenant.workspaceId()),
                memberId, importOnlyRole
        );
        UUID viewerRoleId = seedTenantViewerRole();
        jdbcTemplate.update(
                "UPDATE tenant_memberships SET role_id = ? WHERE tenant_id = ? AND user_id = ?",
                viewerRoleId, UUID.fromString(tenant.tenantId()), memberId
        );

        mockMvc.perform(multipart("/api/v1/app/contacts/import/preview")
                        .file(new MockMultipartFile(
                                "file", "c.csv", "text/csv",
                                "display_name,email,consent_email\nY,y@example.com,OPTED_IN\n"
                                        .getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile(
                                "mapping", "", "application/json",
                                "{\"displayName\":\"display_name\",\"email\":\"email\",\"consentEmailMarketing\":\"consent_email\"}"
                                        .getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void largeExportReturnsAsyncJobThenDownload() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m5c@example.com", "Owner M5C", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenant = createTenant(ownerAccess, "ExportAsync-" + UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/app/contacts")
                            .header("Authorization", "Bearer " + ownerAccess)
                            .header("X-Tenant-Id", tenant.tenantId())
                            .header("X-Workspace-Id", tenant.workspaceId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "displayName", "Export " + i,
                                    "channels", java.util.List.of(Map.of(
                                            "channelType", "EMAIL",
                                            "value", "export-m5-" + i + "@example.com",
                                            "primary", true
                                    ))
                            ))))
                    .andExpect(status().isCreated());
        }

        MvcResult asyncResult = mockMvc.perform(get("/api/v1/app/contacts/export")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .param("status", "ACTIVE"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.async").value(true))
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();
        String jobId = objectMapper.readTree(asyncResult.getResponse().getContentAsString()).get("jobId").asText();

        contactExportService.processExportJob(
                UUID.fromString(tenant.tenantId()),
                UUID.fromString(tenant.workspaceId()),
                UUID.fromString(jobId));

        mockMvc.perform(get("/api/v1/app/contacts/export/jobs/" + jobId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true));

        MvcResult download = mockMvc.perform(get("/api/v1/app/contacts/export/jobs/" + jobId)
                        .param("download", "true")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(download.getResponse().getContentAsString()).contains("export-m5-0@example.com");
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

    private TenantPair createTenant(String access, String name) throws Exception {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        MvcResult result = mockMvc.perform(post("/api/v1/app/tenants")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tenant = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TenantPair(tenant.get("id").asText(), tenant.get("defaultWorkspaceId").asText());
    }

    private UUID seedTenantViewerRole() {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name, scope, description) VALUES (?, ?, 'Viewer', 'TENANT', 'Test-only')",
                roleId, "TENANT_VIEWER_M5_" + roleId.toString().substring(0, 8)
        );
        jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, p.id FROM permissions p WHERE p.code = 'tenant.read'
                """,
                roleId
        );
        return roleId;
    }

    private record TenantPair(String tenantId, String workspaceId) {
    }
}
