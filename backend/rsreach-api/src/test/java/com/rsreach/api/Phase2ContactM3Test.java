package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
        "rsreach.bootstrap.staff-email=owner@contact-m3.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM3Test#dockerAvailable")
class Phase2ContactM3Test {

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

    @Test
    void consentSuppressionEvaluationAuthzIsolationAndHistory() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m3@example.com", "Owner M3", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenantA = createTenant(ownerAccess, "ConsentA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(ownerAccess, "ConsentB-" + UUID.randomUUID());

        String contactId = createContact(ownerAccess, tenantA, "Ada Consent", "ada.consent@example.com", "+919811122233");

        // Missing address fails closed (no primary fallback)
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelType\":\"EMAIL\",\"purpose\":\"MARKETING\"}"))
                .andExpect(status().isBadRequest());

        // Missing consent fails closed for explicit address
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"ada.consent@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasonCode").value("CONSENT_UNKNOWN"));

        // Lazy seed matrix
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(6));

        // Grant EMAIL MARKETING requires evidence for MANUAL
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","status":"OPTED_IN","source":"MANUAL"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","status":"OPTED_IN","source":"MANUAL","evidenceNote":"Verbal opt-in at desk"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPTED_IN"));

        // Channel independence: WhatsApp still unknown
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"WHATSAPP","purpose":"MARKETING","address":"+919811122233"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));

        // Purpose independence: TRANSACTIONAL still unknown
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"TRANSACTIONAL","address":"ada.consent@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));

        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"ada.consent@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true));

        // Suppression blocks eligibility
        MvcResult suppression = mockMvc.perform(post("/api/v1/app/suppressions")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","value":"Ada.Consent@Example.com","reason":"Complaint"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String suppressionId = objectMapper.readTree(suppression.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"ada.consent@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasonCode").value("SUPPRESSED"));

        mockMvc.perform(delete("/api/v1/app/suppressions/" + suppressionId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAt").isNotEmpty());

        // Withdrawal immediately blocks
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","status":"OPTED_OUT","source":"MANUAL","evidenceNote":"Customer request"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPTED_OUT"));

        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"ada.consent@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasonCode").value("OPTED_OUT"));

        // History preserved (seed + grant + withdraw at minimum)
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents/history")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_consent_events WHERE contact_id = ?",
                Integer.class, UUID.fromString(contactId)
        );
        assertThat(eventCount).isGreaterThanOrEqualTo(3);

        // Group membership does not grant consent
        MvcResult group = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Consent Group\",\"groupType\":\"STATIC\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = objectMapper.readTree(group.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/app/contact-groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("contactIds", List.of(contactId)))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"ada.consent@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));

        // Member read-only for consent manage
        JsonNode memberTokens = registerCustomer("member-m3@example.com", "Member M3", "password123");
        String memberAccess = memberTokens.get("accessToken").asText();
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'member-m3@example.com'", UUID.class);
        UUID memberRole = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = 'WORKSPACE_MEMBER'", UUID.class);
        UUID viewerRole = seedTenantViewerRole();
        UUID tenantUuid = UUID.fromString(tenantA.tenantId());
        UUID workspaceUuid = UUID.fromString(tenantA.workspaceId());
        jdbcTemplate.update(
                """
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), tenantUuid, memberId, viewerRole
        );
        jdbcTemplate.update(
                """
                INSERT INTO workspace_memberships
                    (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), tenantUuid, workspaceUuid, memberId, memberRole
        );

        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"SERVICE","status":"OPTED_IN","source":"MANUAL","evidenceNote":"nope"}
                                """))
                .andExpect(status().isForbidden());

        // Isolation + missing context
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .header("X-Workspace-Id", tenantB.workspaceId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess))
                .andExpect(status().isForbidden());

        Integer consentAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'consent.changed'", Integer.class);
        Integer suppressionAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action LIKE 'suppression.%'", Integer.class);
        assertThat(consentAudits).isGreaterThanOrEqualTo(2);
        assertThat(suppressionAudits).isGreaterThanOrEqualTo(2);
    }

    private String createContact(String access, TenantPair tenant, String name, String email, String phone)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
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

    private UUID seedTenantViewerRole() {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name, scope, description) VALUES (?, ?, 'Viewer', 'TENANT', 'Test')",
                roleId, "TENANT_VIEWER_M3_" + roleId.toString().substring(0, 8)
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
