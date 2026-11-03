package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest(properties = {
        "spring.profiles.active=local",
        "rsreach.security.expose-dev-tokens=false",
        "rsreach.jwt.customer.secret=test-customer-jwt-secret-value-32b",
        "rsreach.jwt.staff.secret=test-staff-jwt-secret-value-32b!!!",
        "rsreach.secrets.master-key=test-master-key-material",
        "rsreach.bootstrap.staff-email=owner@contact-m1.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM1Test#dockerAvailable")
class Phase2ContactM1Test {

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

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contactCrudAuthzIsolationCustomFieldsAndAudit() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m1@example.com", "Owner M1", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenantA = createTenant(ownerAccess, "ContactsA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(ownerAccess, "ContactsB-" + UUID.randomUUID());

        MvcResult wsBResult = mockMvc.perform(post("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Workspace B\",\"slug\":\"ws-b-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String workspaceBId = objectMapper.readTree(wsBResult.getResponse().getContentAsString()).get("id").asText();

        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'owner-m1@example.com'", UUID.class);

        // Member with read-only contacts permission in workspace A
        JsonNode memberTokens = registerCustomer("member-m1@example.com", "Member M1", "password123");
        String memberAccess = memberTokens.get("accessToken").asText();
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'member-m1@example.com'", UUID.class);
        UUID tenantAdminRole = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = 'TENANT_ADMIN'", UUID.class);
        UUID memberRole = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = 'WORKSPACE_MEMBER'", UUID.class);
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
        // Downgrade tenant role so manage does not inherit from TENANT_ADMIN
        UUID viewerRoleId = seedTenantViewerRole();
        jdbcTemplate.update(
                "UPDATE tenant_memberships SET role_id = ? WHERE tenant_id = ? AND user_id = ?",
                viewerRoleId, UUID.fromString(tenantA.tenantId()), memberId
        );

        mockMvc.perform(post("/api/v1/app/contact-field-defs")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fieldKey":"company","label":"Company","dataType":"STRING","required":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldKey").value("company"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName":"Ada Lovelace",
                                  "channels":[
                                    {"channelType":"EMAIL","value":"Ada@Example.COM","primary":true},
                                    {"channelType":"PHONE","value":"+91 98765 43210","primary":true}
                                  ],
                                  "customFields":{"company":" Analytical "}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.primaryEmail").value("ada@example.com"))
                .andExpect(jsonPath("$.primaryPhone").value("+919876543210"))
                .andExpect(jsonPath("$.customFields.company").value("Analytical"))
                .andReturn();
        String contactId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // Duplicate identifier within workspace
        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Dup","channels":[{"channelType":"EMAIL","value":"ada@example.com","primary":true}]}
                                """))
                .andExpect(status().isConflict());

        // Same email allowed in another workspace of same tenant
        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Ada WS-B","channels":[{"channelType":"EMAIL","value":"ada@example.com","primary":true}]}
                                """))
                .andExpect(status().isCreated());

        // Invalid custom field rejected
        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Bad","customFields":{"unknown_field":"x"}}
                                """))
                .andExpect(status().isBadRequest());

        // Member can read, cannot manage
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"));

        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Nope\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Hacked\"}"))
                .andExpect(status().isForbidden());

        // Admin update + search
        mockMvc.perform(patch("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName":"Ada L.",
                                  "channels":[{"channelType":"EMAIL","value":"ada@example.com","primary":true}],
                                  "customFields":{"company":"Analytical Engines"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ada L."));

        mockMvc.perform(get("/api/v1/app/contacts")
                        .param("query", "ada")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(contactId))
                .andExpect(jsonPath("$.hasNext").value(false));

        // Cross-workspace isolation (same tenant)
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isNotFound());

        // Cross-tenant isolation
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .header("X-Workspace-Id", tenantB.workspaceId()))
                .andExpect(status().isNotFound());

        // Missing context
        mockMvc.perform(get("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess))
                .andExpect(status().isForbidden());

        // Archive / restore
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // Identifier free after archive
        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Taken","channels":[{"channelType":"EMAIL","value":"ada@example.com","primary":true}]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/restore")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isConflict());

        Integer createdAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'contact.created' AND actor_id = ?",
                Integer.class, ownerId
        );
        Integer archivedAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'contact.archived' AND resource_id = ?",
                Integer.class, contactId
        );
        assertThat(createdAudits).isGreaterThanOrEqualTo(1);
        assertThat(archivedAudits).isEqualTo(1);

        String auditDetails = jdbcTemplate.queryForObject(
                "SELECT details_json::text FROM audit_events WHERE action = 'contact.created' ORDER BY occurred_at LIMIT 1",
                String.class
        );
        assertThat(auditDetails).doesNotContain("ada@example.com");
        assertThat(auditDetails).doesNotContain("+919876543210");
    }

    private UUID seedTenantViewerRole() {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name, scope, description) VALUES (?, ?, 'Viewer', 'TENANT', 'Test-only')",
                roleId, "TENANT_VIEWER_M1_" + roleId.toString().substring(0, 8)
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
}
