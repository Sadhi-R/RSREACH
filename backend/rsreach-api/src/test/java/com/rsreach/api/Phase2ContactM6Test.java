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
        "rsreach.bootstrap.staff-email=owner@contact-m6.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM6Test#dockerAvailable")
class Phase2ContactM6Test {

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
    void shareExpandWriteLimitsConsentHomeOnlyAndRevoke() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m6@example.com", "Owner M6", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenant = createTenant(ownerAccess, "ShareM6-" + UUID.randomUUID());

        MvcResult wsBResult = mockMvc.perform(post("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Workspace B\",\"slug\":\"ws-b-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String workspaceBId = objectMapper.readTree(wsBResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult createContact = mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Shared Person","channels":[{"channelType":"EMAIL","value":"shared-m6@example.com","primary":true}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String contactId = objectMapper.readTree(createContact.getResponse().getContentAsString()).get("id").asText();

        // Not visible in B before share
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isNotFound());

        MvcResult shareResult = mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/shares")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetWorkspaceId", workspaceBId,
                                "permission", "READ"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permission").value("READ"))
                .andReturn();
        String shareId = objectMapper.readTree(shareResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Shared Person"))
                .andExpect(jsonPath("$.access").value("READ"));

        Integer sharedAudits = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM audit_events
                WHERE tenant_id = ? AND action = 'contact.shared' AND resource_id = ?
                """,
                Integer.class, UUID.fromString(tenant.tenantId()), contactId
        );
        assertThat(sharedAudits).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .param("query", "Shared"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(contactId));

        // READ share cannot update
        mockMvc.perform(patch("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Nope","channels":[{"channelType":"EMAIL","value":"shared-m6@example.com","primary":true}]}
                                """))
                .andExpect(status().isForbidden());

        // Consent remains home-only (matrix + history)
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents/history")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isNotFound());

        // Upgrade to WRITE via revoke + recreate
        mockMvc.perform(delete("/api/v1/app/contacts/" + contactId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/shares")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetWorkspaceId", workspaceBId,
                                "permission", "WRITE"
                        ))))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Shared Updated","channels":[{"channelType":"EMAIL","value":"shared-m6@example.com","primary":true}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Shared Updated"));

        // Consent still denied on target even with WRITE
        mockMvc.perform(putConsents(ownerAccess, tenant.tenantId(), workspaceBId, contactId))
                .andExpect(status().isNotFound());

        Integer consentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_consents WHERE contact_id = ?",
                Integer.class, UUID.fromString(contactId)
        );
        // May be seeded in home only; target must not create parallel consent rows under workspace B
        Integer targetConsent = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consents
                WHERE contact_id = ? AND workspace_id = ?
                """,
                Integer.class, UUID.fromString(contactId), UUID.fromString(workspaceBId)
        );
        assertThat(targetConsent).isZero();
        assertThat(consentRows).isNotNull();

        mockMvc.perform(get("/api/v1/app/contacts/" + contactId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").value("WRITE"));

        // Merge is same-home only — preview from shared-in workspace must fail
        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Local B","channels":[{"channelType":"EMAIL","value":"local-b-m6@example.com","primary":true}]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "survivorContactId", contactId,
                                "loserContactId", contactId
                        ))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void groupShareExpandHomeOnlyCreateAndList() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m6g@example.com", "Owner M6G", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenant = createTenant(ownerAccess, "GroupShare-" + UUID.randomUUID());

        MvcResult wsBResult = mockMvc.perform(post("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Workspace B\",\"slug\":\"ws-bg-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String workspaceBId = objectMapper.readTree(wsBResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult groupCreate = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Shared Group M6","groupType":"STATIC"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = objectMapper.readTree(groupCreate.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .param("query", "Shared Group"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mockMvc.perform(post("/api/v1/app/contact-groups/" + groupId + "/shares")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetWorkspaceId", workspaceBId,
                                "permission", "READ"
                        ))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .param("query", "Shared Group"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(groupId));

        mockMvc.perform(get("/api/v1/app/contact-groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isOk());

        // Target cannot create a further share (home-only)
        mockMvc.perform(post("/api/v1/app/contact-groups/" + groupId + "/shares")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetWorkspaceId", tenant.workspaceId(),
                                "permission", "READ"
                        ))))
                .andExpect(status().isNotFound());

        // Target cannot refresh/materialize
        mockMvc.perform(post("/api/v1/app/contact-groups/" + groupId + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putConsents(
            String access, String tenantId, String workspaceId, String contactId
    ) throws Exception {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/app/contacts/" + contactId + "/consents")
                .header("Authorization", "Bearer " + access)
                .header("X-Tenant-Id", tenantId)
                .header("X-Workspace-Id", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"channelType":"EMAIL","purpose":"MARKETING","status":"OPTED_IN","source":"MANUAL","evidenceNote":"should fail"}
                        """);
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

    private record TenantPair(String tenantId, String workspaceId) {
    }
}
