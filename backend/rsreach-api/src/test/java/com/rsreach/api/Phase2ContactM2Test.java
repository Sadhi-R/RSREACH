package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.contacts.ContactGroupMaterializer;
import com.rsreach.contacts.ContactGroupModels;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.time.Instant;
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
        "rsreach.bootstrap.staff-email=owner@contact-m2.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM2Test#dockerAvailable")
class Phase2ContactM2Test {

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
    @Autowired private ContactGroupMaterializer materializer;
    @Autowired private OutboxRepository outboxRepository;

    @Test
    void staticDynamicGroupsAuthzIsolationMaterializationAndAudit() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m2@example.com", "Owner M2", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenantA = createTenant(ownerAccess, "GroupsA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(ownerAccess, "GroupsB-" + UUID.randomUUID());

        MvcResult wsBResult = mockMvc.perform(post("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Workspace B\",\"slug\":\"ws-b-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String workspaceBId = objectMapper.readTree(wsBResult.getResponse().getContentAsString()).get("id").asText();

        // Custom field + contacts
        mockMvc.perform(post("/api/v1/app/contact-field-defs")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldKey\":\"city\",\"label\":\"City\",\"dataType\":\"STRING\",\"required\":false}"))
                .andExpect(status().isCreated());

        String contactPune = createContact(ownerAccess, tenantA, "Ada Pune", "ada.pune@example.com",
                Map.of("city", "Pune"));
        String contactMumbai = createContact(ownerAccess, tenantA, "Bob Mumbai", "bob.mumbai@example.com",
                Map.of("city", "Mumbai"));
        createContact(ownerAccess, tenantA, "Cara Other", "cara@example.com", Map.of());

        // Static group CRUD + members
        MvcResult staticCreate = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"VIP Static\",\"groupType\":\"STATIC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupType").value("STATIC"))
                .andReturn();
        String staticGroupId = objectMapper.readTree(staticCreate.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/app/contact-groups/" + staticGroupId + "/members")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactIds", List.of(contactPune, contactMumbai, contactPune)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        // Duplicate membership skipped
        mockMvc.perform(post("/api/v1/app/contact-groups/" + staticGroupId + "/members")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("contactIds", List.of(contactPune)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.skipped").value(1));

        mockMvc.perform(post("/api/v1/app/contact-groups/" + staticGroupId + "/members/bulk")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "removeContactIds", List.of(contactMumbai)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(1));

        mockMvc.perform(get("/api/v1/app/contact-groups/" + staticGroupId + "/members")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEstimate").value(1));

        // Dynamic group + preview + materialization
        Map<String, Object> filter = Map.of(
                "version", 1,
                "match", "ALL",
                "conditions", List.of(
                        Map.of("field", "custom.city", "op", "eq", "value", "Pune"),
                        Map.of("field", "status", "op", "eq", "value", "ACTIVE")
                )
        );
        mockMvc.perform(post("/api/v1/app/contact-groups/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("filterDefinition", filter))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchCount").value(1));

        MvcResult dynamicCreate = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Pune Dynamic",
                                "groupType", "DYNAMIC",
                                "filterDefinition", filter
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupType").value("DYNAMIC"))
                .andReturn();
        String dynamicGroupId = objectMapper.readTree(dynamicCreate.getResponse().getContentAsString()).get("id").asText();

        Integer pendingOutbox = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM outbox_events
                WHERE event_type = ? AND aggregate_id = ? AND status = 'PENDING'
                """,
                Integer.class, ContactGroupModels.EVENT_CONTACT_GROUP_REFRESH, UUID.fromString(dynamicGroupId)
        );
        assertThat(pendingOutbox).isGreaterThanOrEqualTo(1);

        // Process via materializer (same path as worker handler) twice for idempotency
        UUID tenantUuid = UUID.fromString(tenantA.tenantId());
        UUID workspaceUuid = UUID.fromString(tenantA.workspaceId());
        UUID dynId = UUID.fromString(dynamicGroupId);
        materializer.materialize(tenantUuid, workspaceUuid, dynId);
        materializer.materialize(tenantUuid, workspaceUuid, dynId);

        Integer dynamicMembers = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_group_members
                WHERE group_id = ? AND source = 'DYNAMIC'
                """,
                Integer.class, dynId
        );
        assertThat(dynamicMembers).isEqualTo(1);

        // Manual member preserved across refresh
        mockMvc.perform(post("/api/v1/app/contact-groups/" + dynamicGroupId + "/members")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("contactIds", List.of(contactMumbai)))))
                .andExpect(status().isOk());
        materializer.materialize(tenantUuid, workspaceUuid, dynId);
        Integer manualStillThere = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_group_members
                WHERE group_id = ? AND contact_id = ? AND source = 'MANUAL'
                """,
                Integer.class, dynId, UUID.fromString(contactMumbai)
        );
        assertThat(manualStillThere).isEqualTo(1);

        // Claim outbox path marks processed
        List<UUID> claimed = outboxRepository.claimBatch("m2-test-worker", 50, Instant.now());
        assertThat(claimed).isNotEmpty();
        for (UUID id : claimed) {
            outboxRepository.markProcessed(id);
        }

        // Member read-only
        JsonNode memberTokens = registerCustomer("member-m2@example.com", "Member M2", "password123");
        String memberAccess = memberTokens.get("accessToken").asText();
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'member-m2@example.com'", UUID.class);
        UUID memberRole = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = 'WORKSPACE_MEMBER'", UUID.class);
        UUID viewerRole = seedTenantViewerRole();
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

        mockMvc.perform(get("/api/v1/app/contact-groups/" + staticGroupId)
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\",\"groupType\":\"STATIC\"}"))
                .andExpect(status().isForbidden());

        // Isolation
        mockMvc.perform(get("/api/v1/app/contact-groups/" + staticGroupId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/app/contact-groups/" + staticGroupId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .header("X-Workspace-Id", tenantB.workspaceId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess))
                .andExpect(status().isForbidden());

        // Invalid filter rejected
        mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bad","groupType":"DYNAMIC","filterDefinition":{"version":1,"match":"ALL","conditions":[{"field":"not_a_real_field","op":"eq","value":"x"}]}}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/app/contact-groups/" + staticGroupId + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        Integer groupAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action LIKE 'contact.group.%'",
                Integer.class
        );
        assertThat(groupAudits).isGreaterThanOrEqualTo(3);
    }

    private String createContact(
            String access,
            TenantPair tenant,
            String name,
            String email,
            Map<String, Object> customFields
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", name,
                                "channels", List.of(Map.of("channelType", "EMAIL", "value", email, "primary", true)),
                                "customFields", customFields
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private UUID seedTenantViewerRole() {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name, scope, description) VALUES (?, ?, 'Viewer', 'TENANT', 'Test')",
                roleId, "TENANT_VIEWER_M2_" + roleId.toString().substring(0, 8)
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
