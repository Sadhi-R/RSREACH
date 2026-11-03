package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.contacts.DuplicateCandidateService;
import com.rsreach.contacts.DuplicateModels;
import com.rsreach.persistence.outbox.OutboxEvent;
import com.rsreach.persistence.outbox.OutboxRepository;
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
        "rsreach.bootstrap.staff-email=owner@contact-m41.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM4DetectionTest#dockerAvailable")
class Phase2ContactM4DetectionTest {

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
    @Autowired private DuplicateCandidateService duplicateCandidateService;
    @Autowired private OutboxRepository outboxRepository;

    @Test
    void detectionScanDismissReopenIsolationAndIdempotency() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m41@example.com", "Owner M41", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenantA = createTenant(ownerAccess, "DupA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(ownerAccess, "DupB-" + UUID.randomUUID());

        MvcResult wsBResult = mockMvc.perform(post("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Workspace B\",\"slug\":\"ws-b-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String workspaceBId = objectMapper.readTree(wsBResult.getResponse().getContentAsString()).get("id").asText();

        // 1-4: ACTIVE archive then recreate → exact email candidate; normalization
        String archivedId = createContact(ownerAccess, tenantA, "Archived Ada", "Ada.Dup@Example.com", "+919811100001");
        mockMvc.perform(post("/api/v1/app/contacts/" + archivedId + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        String activeId = createContact(ownerAccess, tenantA, "Active Ada", "ada.dup@example.com", "+919811100002");

        mockMvc.perform(get("/api/v1/app/contact-duplicates")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].confidence").value("HIGH"));

        String candidateId = objectMapper.readTree(mockMvc.perform(get("/api/v1/app/contact-duplicates")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andReturn().getResponse().getContentAsString()).get("items").get(0).get("id").asText();

        // 2: phone match via second archived/recreate
        String archivedPhone = createContact(ownerAccess, tenantA, "Phone Arch", "phone.arch@example.com", "+919822200001");
        mockMvc.perform(post("/api/v1/app/contacts/" + archivedPhone + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        createContact(ownerAccess, tenantA, "Phone Act", "phone.act@example.com", "+91 98222-00001");

        // 3: email+phone both match
        String bothArch = createContact(ownerAccess, tenantA, "Both Arch", "both@example.com", "+919833300001");
        mockMvc.perform(post("/api/v1/app/contacts/" + bothArch + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        createContact(ownerAccess, tenantA, "Both Act", "both@example.com", "+919833300001");

        Integer veryHigh = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_duplicate_candidates
                WHERE tenant_id = ? AND workspace_id = ? AND confidence = 'VERY_HIGH' AND status = 'OPEN'
                """,
                Integer.class, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId())
        );
        assertThat(veryHigh).isGreaterThanOrEqualTo(1);

        // 5: ACTIVE↔ACTIVE same email blocked by unique index
        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "Dup Active",
                                "channels", List.of(Map.of("channelType", "EMAIL", "value", "ada.dup@example.com", "primary", true)),
                                "customFields", Map.of()
                        ))))
                .andExpect(status().isConflict());

        // 6: INVALID ignored — insert invalid channel via SQL and ensure no candidate with it alone
        UUID invalidContact = UUID.fromString(createContact(
                ownerAccess, tenantA, "Invalid Only", "invalid.only@example.com", "+919844400001"));
        jdbcTemplate.update(
                """
                UPDATE contact_channels SET status = 'INVALID'
                WHERE contact_id = ? AND channel_type = 'EMAIL'
                """,
                invalidContact
        );
        UUID otherArchived = UUID.fromString(createContact(
                ownerAccess, tenantA, "Other Arch", "other.invalid@example.com", "+919844400099"));
        mockMvc.perform(post("/api/v1/app/contacts/" + otherArchived + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        jdbcTemplate.update(
                """
                UPDATE contact_channels SET value_normalized = 'invalid.only@example.com', value_raw = 'invalid.only@example.com'
                WHERE contact_id = ? AND channel_type = 'EMAIL'
                """,
                otherArchived
        );
        duplicateCandidateService.processScan(
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()));
        Integer invalidPairs = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_duplicate_candidates c
                WHERE c.tenant_id = ? AND c.workspace_id = ?
                  AND (c.contact_id_a = ? OR c.contact_id_b = ?)
                  AND c.status = 'OPEN'
                """,
                Integer.class,
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                invalidContact, invalidContact
        );
        assertThat(invalidPairs).isZero();

        // 7: cross-workspace never persisted
        String wsBContact = createContact(
                ownerAccess,
                new TenantPair(tenantA.tenantId(), workspaceBId),
                "WSB",
                "ada.dup@example.com",
                "+919855500001"
        );
        duplicateCandidateService.processScan(
                UUID.fromString(tenantA.tenantId()), UUID.fromString(workspaceBId));
        Integer crossWs = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_duplicate_candidates
                WHERE tenant_id = ?
                  AND contact_id_a = LEAST(?::uuid, ?::uuid)
                  AND contact_id_b = GREATEST(?::uuid, ?::uuid)
                """,
                Integer.class, UUID.fromString(tenantA.tenantId()),
                UUID.fromString(wsBContact), UUID.fromString(activeId),
                UUID.fromString(wsBContact), UUID.fromString(activeId)
        );
        assertThat(crossWs).isZero();

        // 8: cross-tenant
        mockMvc.perform(get("/api/v1/app/contact-duplicates/" + candidateId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .header("X-Workspace-Id", tenantB.workspaceId()))
                .andExpect(status().isNotFound());

        // 9: canonical UUID ordering matches PostgreSQL
        UUID a = UUID.fromString(archivedId);
        UUID b = UUID.fromString(activeId);
        UUID pgLow = jdbcTemplate.queryForObject("SELECT LEAST(?::uuid, ?::uuid)", UUID.class, a, b);
        UUID storedLow = jdbcTemplate.queryForObject(
                "SELECT contact_id_a FROM contact_duplicate_candidates WHERE id = ?",
                UUID.class, UUID.fromString(candidateId)
        );
        assertThat(storedLow).isEqualTo(pgLow);

        // 17-18: detail resolves values; evidence has no email strings
        MvcResult detail = mockMvc.perform(get("/api/v1/app/contact-duplicates/" + candidateId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].valueNormalized").value("ada.dup@example.com"))
                .andReturn();
        String evidenceJson = jdbcTemplate.queryForObject(
                "SELECT evidence::text FROM contact_duplicate_candidates WHERE id = ?",
                String.class, UUID.fromString(candidateId)
        );
        assertThat(evidenceJson).doesNotContain("ada.dup@example.com");
        assertThat(evidenceJson).contains("channelIdA");

        // 11: dismiss sticky across rescan
        mockMvc.perform(post("/api/v1/app/contact-duplicates/" + candidateId + "/dismiss")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not the same person\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
        duplicateCandidateService.processScan(
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()));
        String statusAfterScan = jdbcTemplate.queryForObject(
                "SELECT status FROM contact_duplicate_candidates WHERE id = ?",
                String.class, UUID.fromString(candidateId)
        );
        assertThat(statusAfterScan).isEqualTo("DISMISSED");

        // 12 / 19: member cannot reopen; admin can
        JsonNode memberTokens = registerCustomer("member-m41@example.com", "Member M41", "password123");
        String memberAccess = memberTokens.get("accessToken").asText();
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'member-m41@example.com'", UUID.class);
        UUID memberRole = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = 'WORKSPACE_MEMBER'", UUID.class);
        UUID viewerRole = seedTenantViewerRole();
        jdbcTemplate.update(
                """
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), UUID.fromString(tenantA.tenantId()), memberId, viewerRole
        );
        jdbcTemplate.update(
                """
                INSERT INTO workspace_memberships
                    (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), UUID.fromString(tenantA.tenantId()),
                UUID.fromString(tenantA.workspaceId()), memberId, memberRole
        );
        mockMvc.perform(post("/api/v1/app/contact-duplicates/scans")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/app/contact-duplicates/" + candidateId + "/dismiss")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"member attempt\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/app/contact-duplicates/" + candidateId + "/reopen")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/app/contact-duplicates/" + candidateId + "/reopen")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 14: changed identifier supersedes
        jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET value_normalized = 'changed.dup@example.com', value_raw = 'changed.dup@example.com'
                WHERE contact_id = ? AND channel_type = 'EMAIL'
                """,
                UUID.fromString(archivedId)
        );
        mockMvc.perform(get("/api/v1/app/contact-duplicates/" + candidateId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.stale").value(true));

        // Restore match then invalidate channel → supersede (15)
        jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET value_normalized = 'ada.dup@example.com', value_raw = 'Ada.Dup@Example.com', status = 'ARCHIVED'
                WHERE contact_id = ? AND channel_type = 'EMAIL'
                """,
                UUID.fromString(archivedId)
        );
        // Force OPEN again via upsert path
        duplicateCandidateService.processScan(
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()));
        String restoredId = jdbcTemplate.queryForObject(
                """
                SELECT id::text FROM contact_duplicate_candidates
                WHERE tenant_id = ? AND workspace_id = ?
                  AND contact_id_a = LEAST(?::uuid, ?::uuid)
                  AND contact_id_b = GREATEST(?::uuid, ?::uuid)
                """,
                String.class,
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(archivedId), UUID.fromString(activeId),
                UUID.fromString(archivedId), UUID.fromString(activeId)
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM contact_duplicate_candidates WHERE id = ?",
                String.class, UUID.fromString(restoredId)
        )).isEqualTo("OPEN");
        jdbcTemplate.update(
                "UPDATE contact_channels SET status = 'INVALID' WHERE contact_id = ? AND channel_type = 'EMAIL'",
                UUID.fromString(archivedId)
        );
        mockMvc.perform(get("/api/v1/app/contact-duplicates/" + restoredId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"));
        mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", restoredId,
                                "survivorContactId", activeId,
                                "loserContactId", archivedId
                        ))))
                .andExpect(status().isConflict());

        // 13: remove identifier → supersede
        jdbcTemplate.update(
                """
                UPDATE contact_channels
                SET value_normalized = 'ada.dup@example.com', value_raw = 'Ada.Dup@Example.com', status = 'ARCHIVED'
                WHERE contact_id = ? AND channel_type = 'EMAIL'
                """,
                UUID.fromString(archivedId)
        );
        duplicateCandidateService.processScan(
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()));
        String openAgainId = jdbcTemplate.queryForObject(
                """
                SELECT id::text FROM contact_duplicate_candidates
                WHERE tenant_id = ? AND workspace_id = ?
                  AND contact_id_a = LEAST(?::uuid, ?::uuid)
                  AND contact_id_b = GREATEST(?::uuid, ?::uuid)
                  AND status = 'OPEN'
                """,
                String.class,
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(archivedId), UUID.fromString(activeId),
                UUID.fromString(archivedId), UUID.fromString(activeId)
        );
        jdbcTemplate.update(
                "DELETE FROM contact_channels WHERE contact_id = ? AND channel_type = 'EMAIL'",
                UUID.fromString(archivedId)
        );
        mockMvc.perform(get("/api/v1/app/contact-duplicates/" + openAgainId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.stale").value(true));

        // 20: missing context
        mockMvc.perform(get("/api/v1/app/contact-duplicates")
                        .header("Authorization", "Bearer " + ownerAccess))
                .andExpect(status().isForbidden());

        // 21: scan enqueue idempotent coalesce + worker process
        MvcResult scan1 = mockMvc.perform(post("/api/v1/app/contact-duplicates/scans")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isAccepted())
                .andReturn();
        String scanId1 = objectMapper.readTree(scan1.getResponse().getContentAsString()).get("scanId").asText();
        MvcResult scan2 = mockMvc.perform(post("/api/v1/app/contact-duplicates/scans")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isAccepted())
                .andReturn();
        String scanId2 = objectMapper.readTree(scan2.getResponse().getContentAsString()).get("scanId").asText();
        assertThat(scanId2).isEqualTo(scanId1);

        OutboxEvent event = outboxRepository.findById(UUID.fromString(scanId1)).orElseThrow();
        assertThat(event.eventType()).isEqualTo(DuplicateModels.EVENT_CONTACT_DUPLICATE_SCAN);
        duplicateCandidateService.processScan(event.tenantId(), event.workspaceId());
        duplicateCandidateService.processScan(event.tenantId(), event.workspaceId());

        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action LIKE 'contact.duplicate.%'",
                Integer.class
        );
        assertThat(audits).isGreaterThanOrEqualTo(2);

        // 16: merged-away excluded — mark contact as merge loser
        jdbcTemplate.update(
                """
                UPDATE contacts
                SET merged_into_contact_id = ?, merged_at = NOW(), status = 'ARCHIVED', archived_at = NOW()
                WHERE id = ?
                """,
                UUID.fromString(activeId), UUID.fromString(archivedId)
        );
        duplicateCandidateService.processScan(
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()));
        Integer involvingLoser = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_duplicate_candidates
                WHERE tenant_id = ? AND workspace_id = ? AND status = 'OPEN'
                  AND (contact_id_a = ? OR contact_id_b = ?)
                """,
                Integer.class,
                UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(archivedId), UUID.fromString(archivedId)
        );
        assertThat(involvingLoser).isZero();
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
                roleId, "TENANT_VIEWER_M41_" + roleId.toString().substring(0, 8)
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
