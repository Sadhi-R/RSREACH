package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.contacts.ConsentEvaluationService;
import com.rsreach.contacts.ConsentModels;
import com.rsreach.contacts.ConsentService;
import com.rsreach.contacts.ContactGroupMaterializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        "rsreach.bootstrap.staff-email=owner@contact-m3v.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM3SecurityVerificationTest#dockerAvailable")
class Phase2ContactM3SecurityVerificationTest {

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
    @Autowired private ConsentService consentService;
    @Autowired private ConsentEvaluationService evaluationService;
    @Autowired private ContactGroupMaterializer materializer;

    @Test
    void workspaceIsolationSharingEligibilityHistoryConcurrencyAndFailClosed() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m3v@example.com", "Owner M3V", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenantA = createTenant(ownerAccess, "ConsentVerA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(ownerAccess, "ConsentVerB-" + UUID.randomUUID());

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
                "SELECT id FROM customer_users WHERE email_normalized = 'owner-m3v@example.com'", UUID.class);
        UUID tenantUuid = UUID.fromString(tenantA.tenantId());
        UUID workspaceA = UUID.fromString(tenantA.workspaceId());
        UUID workspaceB = UUID.fromString(workspaceBId);

        // Owner is admin of workspace B via TENANT_ADMIN inheritance when creating workspace;
        // ensure membership exists for workspace B as creator.
        Integer wsBMembership = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM workspace_memberships
                WHERE tenant_id = ? AND workspace_id = ? AND user_id = ? AND status = 'ACTIVE'
                """,
                Integer.class, tenantUuid, workspaceB, ownerId
        );
        if (wsBMembership == null || wsBMembership == 0) {
            UUID adminRole = jdbcTemplate.queryForObject(
                    "SELECT id FROM roles WHERE code = 'WORKSPACE_ADMIN'", UUID.class);
            jdbcTemplate.update(
                    """
                    INSERT INTO workspace_memberships
                        (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                    """,
                    UUID.randomUUID(), tenantUuid, workspaceB, ownerId, adminRole
            );
        }

        String contactId = createContactMultiEmail(
                ownerAccess, tenantA,
                "Ada Verify",
                "ada.primary@example.com",
                "ada.secondary@example.com",
                "+919822233344"
        );
        UUID contactUuid = UUID.fromString(contactId);

        // 1) Workspace B cannot modify Workspace A consent
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","status":"OPTED_IN","source":"MANUAL","evidenceNote":"cross-ws"}
                                """))
                .andExpect(status().isNotFound());

        // Seed + grant in home workspace
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(tenantA.workspaceId()))
                .andExpect(jsonPath("$.items.length()").value(6));

        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","status":"OPTED_IN","source":"MANUAL","evidenceNote":"Home opt-in"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(tenantA.workspaceId()))
                .andExpect(jsonPath("$.status").value("OPTED_IN"));

        // 2) Explicit resource share WRITE does not grant consent-management authority
        jdbcTemplate.update(
                """
                INSERT INTO resource_shares (
                    id, tenant_id, source_workspace_id, target_workspace_id,
                    resource_type, resource_id, permission, created_by, created_at
                ) VALUES (?, ?, ?, ?, 'CONTACT', ?, 'WRITE', ?, NOW())
                """,
                UUID.randomUUID(), tenantUuid, workspaceA, workspaceB, contactUuid, ownerId
        );
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"SERVICE","status":"OPTED_IN","source":"MANUAL","evidenceNote":"via share"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isNotFound());

        // 3) Cross-tenant consent access rejected
        mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .header("X-Workspace-Id", tenantB.workspaceId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .header("X-Workspace-Id", tenantB.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","status":"OPTED_OUT","source":"MANUAL","evidenceNote":"x-tenant"}
                                """))
                .andExpect(status().isNotFound());

        // 4) Dynamic group consent filter uses home workspace scope
        Map<String, Object> consentFilter = Map.of(
                "version", 1,
                "match", "ALL",
                "conditions", List.of(
                        Map.of("field", "consent.EMAIL.MARKETING", "op", "eq", "value", "OPTED_IN")
                )
        );
        MvcResult dynGroup = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Opted In Email Marketing",
                                "groupType", "DYNAMIC",
                                "filterDefinition", consentFilter
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = objectMapper.readTree(dynGroup.getResponse().getContentAsString()).get("id").asText();
        materializer.materialize(tenantUuid, workspaceA, UUID.fromString(groupId));
        Integer memberCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_group_members
                WHERE group_id = ? AND contact_id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                Integer.class, UUID.fromString(groupId), contactUuid, tenantUuid, workspaceA
        );
        assertThat(memberCount).isEqualTo(1);

        // Confirm filter SQL binds to home_workspace_id (no foreign workspace consent rows)
        Integer foreignConsent = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consents
                WHERE contact_id = ? AND workspace_id = ?
                """,
                Integer.class, contactUuid, workspaceB
        );
        assertThat(foreignConsent).isZero();

        // 5) Suppression overrides OPTED_IN for exact address
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"ada.primary@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true));

        MvcResult suppression = mockMvc.perform(post("/api/v1/app/suppressions")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","value":"Ada.Primary@Example.com","reason":"Complaint"}
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
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"ada.primary@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasonCode").value("SUPPRESSED"));

        // Secondary address on contact is still eligible (suppression is address-exact)
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"ada.secondary@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true));

        // 6) Release is authorized + audited; DELETE is logical release
        mockMvc.perform(delete("/api/v1/app/suppressions/" + suppressionId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", workspaceBId))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/app/suppressions/" + suppressionId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAt").isNotEmpty());

        Integer suppressionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_suppressions WHERE id = ?",
                Integer.class, UUID.fromString(suppressionId)
        );
        assertThat(suppressionRows).isEqualTo(1);
        Integer releaseAudits = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'suppression.released' AND resource_id = ?
                """,
                Integer.class, suppressionId
        );
        assertThat(releaseAudits).isEqualTo(1);

        // 7) Exact-address eligibility does not inherit from another address
        mockMvc.perform(post("/api/v1/app/contacts/" + contactId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","address":"not-on-contact@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasonCode").value("ADDRESS_NOT_ON_CONTACT"));

        // 8) Grant/withdraw history remains consistent + append-only at DB
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"EMAIL","purpose":"MARKETING","status":"OPTED_OUT","source":"MANUAL","evidenceNote":"Withdraw"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPTED_OUT"));

        MvcResult history = mockMvc.perform(get("/api/v1/app/contacts/" + contactId + "/consents/history")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].workspaceId").value(tenantA.workspaceId()))
                .andReturn();
        JsonNode historyItems = objectMapper.readTree(history.getResponse().getContentAsString()).get("items");
        assertThat(historyItems.toString()).contains("GRANTED").contains("WITHDRAWN").contains("SEEDED_UNKNOWN");

        UUID eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM contact_consent_events WHERE contact_id = ? LIMIT 1",
                UUID.class, contactUuid
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE contact_consent_events SET new_status = 'OPTED_IN' WHERE id = ?", eventId
        )).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM contact_consent_events WHERE id = ?", eventId
        )).hasMessageContaining("append-only");

        String currentStatus = jdbcTemplate.queryForObject(
                """
                SELECT status FROM contact_consents
                WHERE contact_id = ? AND channel_type = 'EMAIL' AND purpose = 'MARKETING'
                """,
                String.class, contactUuid
        );
        assertThat(currentStatus).isEqualTo("OPTED_OUT");

        // 9) Concurrent consent updates stay consistent
        CountDownLatch startUpdates = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> updateFutures = new ArrayList<>();
        AtomicInteger updateOk = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            final int idx = i;
            updateFutures.add(pool.submit(() -> {
                startUpdates.await(10, TimeUnit.SECONDS);
                String status = idx % 2 == 0 ? "OPTED_IN" : "OPTED_OUT";
                consentService.change(
                        tenantUuid, workspaceA, ownerId, contactUuid,
                        new ConsentModels.ConsentChangeCommand(
                                "EMAIL", "TRANSACTIONAL", status, "MANUAL", "Concurrent " + idx)
                );
                updateOk.incrementAndGet();
                return null;
            }));
        }
        startUpdates.countDown();
        for (Future<?> f : updateFutures) {
            f.get(30, TimeUnit.SECONDS);
        }
        assertThat(updateOk.get()).isEqualTo(8);
        Integer transactionalRows = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consents
                WHERE contact_id = ? AND channel_type = 'EMAIL' AND purpose = 'TRANSACTIONAL'
                """,
                Integer.class, contactUuid
        );
        assertThat(transactionalRows).isEqualTo(1);
        Integer transactionalEvents = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consent_events
                WHERE contact_id = ? AND channel_type = 'EMAIL' AND purpose = 'TRANSACTIONAL'
                  AND event_type IN ('GRANTED', 'WITHDRAWN')
                """,
                Integer.class, contactUuid
        );
        assertThat(transactionalEvents).isGreaterThanOrEqualTo(1);

        // 10) Concurrent UNKNOWN initialization is idempotent
        String contact2 = createContact(
                ownerAccess, tenantA, "Concurrent Seed", "seed.concurrent@example.com", "+919833344455");
        UUID contact2Uuid = UUID.fromString(contact2);
        CountDownLatch startSeed = new CountDownLatch(1);
        List<Future<?>> seedFutures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            seedFutures.add(pool.submit(() -> {
                startSeed.await(10, TimeUnit.SECONDS);
                consentService.getOrSeedMatrix(tenantUuid, workspaceA, contact2Uuid, ownerId);
                return null;
            }));
        }
        startSeed.countDown();
        for (Future<?> f : seedFutures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        Integer consentCells = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_consents WHERE contact_id = ?",
                Integer.class, contact2Uuid
        );
        Integer seedEvents = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consent_events
                WHERE contact_id = ? AND event_type = 'SEEDED_UNKNOWN'
                """,
                Integer.class, contact2Uuid
        );
        assertThat(consentCells).isEqualTo(6);
        assertThat(seedEvents).isEqualTo(6);
        Integer optedInSeeds = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consents
                WHERE contact_id = ? AND status = 'OPTED_IN'
                """,
                Integer.class, contact2Uuid
        );
        assertThat(optedInSeeds).isZero();

        // 11) DB uniqueness includes workspace_id (V14)
        String uniqueDef = jdbcTemplate.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'uq_contact_consent'
                """,
                String.class
        );
        assertThat(uniqueDef).contains("workspace_id");

        // Fail-closed: evaluation never returns ELIGIBLE without an address belonging to the contact
        ConsentModels.EligibilityResult noAddress = evaluationService.evaluate(
                tenantUuid, workspaceA, contactUuid, "EMAIL", "MARKETING", " ");
        assertThat(noAddress.eligible()).isFalse();
        assertThat(noAddress.reasonCode()).isEqualTo("ADDRESS_REQUIRED");
    }

    private String createContactMultiEmail(
            String access,
            TenantPair tenant,
            String name,
            String emailPrimary,
            String emailSecondary,
            String phone
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", name,
                                "channels", List.of(
                                        Map.of("channelType", "EMAIL", "value", emailPrimary, "primary", true),
                                        Map.of("channelType", "EMAIL", "value", emailSecondary, "primary", false),
                                        Map.of("channelType", "PHONE", "value", phone, "primary", true)
                                ),
                                "customFields", Map.of()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
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
