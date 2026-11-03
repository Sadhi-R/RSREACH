package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
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
        "rsreach.bootstrap.staff-email=owner@contact-m42.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase2ContactM4MergeTest#dockerAvailable")
class Phase2ContactM4MergeTest {

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
    void mergePreviewExecuteConsentGroupsSharesIdempotencyAndIsolation() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-m42@example.com", "Owner M42", "password123");
        String ownerAccess = ownerTokens.get("accessToken").asText();
        TenantPair tenantA = createTenant(ownerAccess, "MergeA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(ownerAccess, "MergeB-" + UUID.randomUUID());

        // Create loser, attach to static group while ACTIVE, then archive and recreate → OPEN candidate
        String loserId = createContact(ownerAccess, tenantA, "Loser Ada", "merge.ada@example.com", "+919900000001");
        grantConsent(ownerAccess, tenantA, loserId, "EMAIL", "MARKETING", "OPTED_IN");
        grantConsent(ownerAccess, tenantA, loserId, "WHATSAPP", "MARKETING", "OPTED_IN");
        String staticGroupId = createStaticGroup(ownerAccess, tenantA, "merge-static");
        mockMvc.perform(post("/api/v1/app/contact-groups/" + staticGroupId + "/members")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("contactIds", List.of(loserId)))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/app/contacts/" + loserId + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());

        String survivorId = createContact(ownerAccess, tenantA, "Survivor Ada", "merge.ada@example.com", "+919900000002");
        grantConsent(ownerAccess, tenantA, survivorId, "EMAIL", "MARKETING", "OPTED_IN");
        grantConsent(ownerAccess, tenantA, survivorId, "WHATSAPP", "MARKETING", "OPTED_IN");

        // Extra ACTIVE phone on loser (distinct) — SQL insert on archived loser
        UUID loserPhoneMove = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_channels (
                    id, tenant_id, workspace_id, contact_id, channel_type, value_raw, value_normalized,
                    is_primary, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PHONE', '+919900000099', '+919900000099', FALSE, 'ACTIVE', NOW(), NOW())
                """,
                loserPhoneMove, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(loserId)
        );
        UUID survivorArchivedEmail = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_channels (
                    id, tenant_id, workspace_id, contact_id, channel_type, value_raw, value_normalized,
                    is_primary, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'EMAIL', 'survivor.old@example.com', 'survivor.old@example.com', FALSE, 'ARCHIVED', NOW(), NOW())
                """,
                survivorArchivedEmail, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(survivorId)
        );
        UUID loserArchivedEmail = UUID.randomUUID();
        UUID loserInvalidEmail = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contact_channels (
                    id, tenant_id, workspace_id, contact_id, channel_type, value_raw, value_normalized,
                    is_primary, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'EMAIL', 'old.merge@example.com', 'old.merge@example.com', FALSE, 'ARCHIVED', NOW(), NOW())
                """,
                loserArchivedEmail, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(loserId)
        );
        jdbcTemplate.update(
                """
                INSERT INTO contact_channels (
                    id, tenant_id, workspace_id, contact_id, channel_type, value_raw, value_normalized,
                    is_primary, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'EMAIL', 'bad.merge@example.com', 'bad.merge@example.com', FALSE, 'INVALID', NOW(), NOW())
                """,
                loserInvalidEmail, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(loserId)
        );

        String candidateId = objectMapper.readTree(mockMvc.perform(get("/api/v1/app/contact-duplicates")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString()).get("items").get(0).get("id").asText();

        String dynamicGroupId = createDynamicGroup(ownerAccess, tenantA, "merge-dyn");

        // Active loser share to another workspace in same tenant
        MvcResult wsB = mockMvc.perform(post("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Share Target\",\"slug\":\"share-tgt-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String targetWs = objectMapper.readTree(wsB.getResponse().getContentAsString()).get("id").asText();
        UUID shareId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO resource_shares (
                    id, tenant_id, source_workspace_id, target_workspace_id,
                    resource_type, resource_id, permission, created_by, created_at
                ) VALUES (?, ?, ?, ?, 'CONTACT', ?, 'READ', ?, NOW())
                """,
                shareId,
                UUID.fromString(tenantA.tenantId()),
                UUID.fromString(tenantA.workspaceId()),
                UUID.fromString(targetWs),
                UUID.fromString(loserId),
                jdbcTemplate.queryForObject(
                        "SELECT id FROM customer_users WHERE email_normalized = 'owner-m42@example.com'", UUID.class)
        );

        // Suppression on introduced phone must remain
        mockMvc.perform(post("/api/v1/app/suppressions")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"WHATSAPP","value":"+919900000099","reason":"COMPLAINT"}
                                """))
                .andExpect(status().isCreated());

        // Preview
        MvcResult previewResult = mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidateId,
                                "survivorContactId", survivorId,
                                "loserContactId", loserId,
                                "fieldChoices", List.of(),
                                "primaryChoices", List.of()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeAllowed").value(true))
                .andExpect(jsonPath("$.channels").isArray())
                .andReturn();
        JsonNode preview = objectMapper.readTree(previewResult.getResponse().getContentAsString());
        String previewHash = preview.get("previewHash").asText();

        assertThat(preview.findValuesAsText("action")).contains("MOVED", "LEFT_ARCHIVED", "LEFT_INVALID", "RETAINED");
        assertThat(preview.get("introducedAddresses").toString()).contains("+919900000099");
        assertThat(preview.get("consent").toString()).contains("UNKNOWN"); // introduced phone → WHATSAPP UNKNOWN
        assertThat(preview.get("sharesToRevoke")).isNotEmpty();

        // Cross-tenant preview 404
        mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .header("X-Workspace-Id", tenantB.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidateId,
                                "survivorContactId", survivorId,
                                "loserContactId", loserId
                        ))))
                .andExpect(status().isNotFound());

        // Member cannot merge
        JsonNode memberTokens = registerCustomer("member-m42@example.com", "Member M42", "password123");
        String memberAccess = memberTokens.get("accessToken").asText();
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'member-m42@example.com'", UUID.class);
        UUID memberRole = jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = 'WORKSPACE_MEMBER'", UUID.class);
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
        mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + memberAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidateId,
                                "survivorContactId", survivorId,
                                "loserContactId", loserId
                        ))))
                .andExpect(status().isForbidden());

        // Stale preview hash
        mockMvc.perform(post("/api/v1/app/contacts/merges")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", "merge-stale-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidateId,
                                "survivorContactId", survivorId,
                                "loserContactId", loserId,
                                "previewHash", "deadbeef",
                                "confirm", true
                        ))))
                .andExpect(status().isConflict());

        // Execute
        String idemKey = "merge-ok-" + UUID.randomUUID();
        Map<String, Object> executeBody = Map.of(
                "candidateId", candidateId,
                "survivorContactId", survivorId,
                "loserContactId", loserId,
                "fieldChoices", List.of(),
                "primaryChoices", List.of(),
                "previewHash", previewHash,
                "confirm", true
        );
        MvcResult mergeResult = mockMvc.perform(post("/api/v1/app/contacts/merges")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executeBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();
        JsonNode merge = objectMapper.readTree(mergeResult.getResponse().getContentAsString());
        String mergeId = merge.get("id").asText();

        // Idempotent replay
        MvcResult replay = mockMvc.perform(post("/api/v1/app/contacts/merges")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executeBody)))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asText())
                .isEqualTo(mergeId);

        // Same key different body
        mockMvc.perform(post("/api/v1/app/contacts/merges")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidateId,
                                "survivorContactId", loserId,
                                "loserContactId", survivorId,
                                "previewHash", previewHash,
                                "confirm", true
                        ))))
                .andExpect(status().isConflict());

        // Loser marked merged; restore blocked
        assertThat(jdbcTemplate.queryForObject(
                "SELECT merged_into_contact_id::text FROM contacts WHERE id = ?",
                String.class, UUID.fromString(loserId)
        )).isEqualTo(survivorId);
        mockMvc.perform(post("/api/v1/app/contacts/" + loserId + "/restore")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isConflict());

        // Channel dispositions applied
        Integer movedOnSurvivor = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_channels
                WHERE contact_id = ? AND value_normalized = '+919900000099' AND status = 'ACTIVE'
                """,
                Integer.class, UUID.fromString(survivorId)
        );
        assertThat(movedOnSurvivor).isEqualTo(1);
        Integer leftOnLoser = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_channels
                WHERE contact_id = ? AND id IN (?, ?)
                """,
                Integer.class, UUID.fromString(loserId), loserArchivedEmail, loserInvalidEmail
        );
        assertThat(leftOnLoser).isEqualTo(2);

        // Consent events on survivor only for changes; loser events untouched count
        Integer mergeEvents = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consent_events
                WHERE contact_id = ? AND event_type = 'MERGE_RECONCILED'
                """,
                Integer.class, UUID.fromString(survivorId)
        );
        assertThat(mergeEvents).isGreaterThanOrEqualTo(1);
        Integer loserMergeEvents = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_consent_events
                WHERE contact_id = ? AND event_type = 'MERGE_RECONCILED'
                """,
                Integer.class, UUID.fromString(loserId)
        );
        assertThat(loserMergeEvents).isZero();

        // Suppression unchanged
        Integer suppressions = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_suppressions
                WHERE tenant_id = ? AND workspace_id = ?
                  AND value_normalized = '+919900000099' AND released_at IS NULL
                """,
                Integer.class, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId())
        );
        assertThat(suppressions).isEqualTo(1);

        // Eligibility still fail-closed on suppressed introduced phone
        mockMvc.perform(post("/api/v1/app/contacts/" + survivorId + "/consent-eligibility")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"WHATSAPP","purpose":"MARKETING","address":"+919900000099"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));

        // Groups: static union + loser removed; dynamic refresh outbox
        Integer survivorInStatic = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_group_members
                WHERE group_id = ? AND contact_id = ? AND source = 'MANUAL'
                """,
                Integer.class, UUID.fromString(staticGroupId), UUID.fromString(survivorId)
        );
        assertThat(survivorInStatic).isEqualTo(1);
        Integer loserInStatic = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_group_members
                WHERE group_id = ? AND contact_id = ?
                """,
                Integer.class, UUID.fromString(staticGroupId), UUID.fromString(loserId)
        );
        assertThat(loserInStatic).isZero();
        Integer refreshEvents = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM outbox_events
                WHERE event_type = 'CONTACT_GROUP_REFRESH' AND aggregate_id = ?
                """,
                Integer.class, UUID.fromString(dynamicGroupId)
        );
        assertThat(refreshEvents).isGreaterThanOrEqualTo(1);

        // Shares revoked, not transferred
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resource_shares WHERE id = ? AND revoked_at IS NOT NULL",
                Integer.class, shareId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM resource_shares
                WHERE resource_type = 'CONTACT' AND resource_id = ? AND revoked_at IS NULL
                """,
                Integer.class, UUID.fromString(survivorId)
        )).isZero();

        Integer shareAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'contact.share_revoked'",
                Integer.class
        );
        assertThat(shareAudits).isGreaterThanOrEqualTo(1);
        Integer mergedAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'contact.merged' AND resource_id = ?",
                Integer.class, mergeId
        );
        assertThat(mergedAudits).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM contact_merge_items
                WHERE merge_id = ? AND item_type = 'SHARE' AND action = 'REVOKED' AND item_key = ?
                """,
                Integer.class, UUID.fromString(mergeId), shareId.toString()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM contact_channels WHERE id = ?",
                String.class, survivorArchivedEmail
        )).isEqualTo("ARCHIVED");

        // PII not in merge items
        String itemsJson = jdbcTemplate.queryForObject(
                "SELECT COALESCE(string_agg(before_json::text || after_json::text, ''), '') FROM contact_merge_items WHERE merge_id = ?",
                String.class, UUID.fromString(mergeId)
        );
        assertThat(itemsJson).doesNotContain("merge.ada@example.com");
        assertThat(itemsJson).doesNotContain("+919900000099");

        // History API
        mockMvc.perform(get("/api/v1/app/contacts/merges/" + mergeId)
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mergeId));

        // Stale candidate after identifier removal on a fresh pair
        String loser2 = createContact(ownerAccess, tenantA, "Loser2", "stale.merge@example.com", "+919900000111");
        mockMvc.perform(post("/api/v1/app/contacts/" + loser2 + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        String survivor2 = createContact(ownerAccess, tenantA, "Survivor2", "stale.merge@example.com", "+919900000112");
        String candidate2 = objectMapper.readTree(mockMvc.perform(get("/api/v1/app/contact-duplicates")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .param("contactId", survivor2))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("items").get(0).get("id").asText();
        jdbcTemplate.update(
                "DELETE FROM contact_channels WHERE contact_id = ? AND channel_type = 'EMAIL'",
                UUID.fromString(loser2)
        );
        mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidate2,
                                "survivorContactId", survivor2,
                                "loserContactId", loser2
                        ))))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM contact_duplicate_candidates WHERE id = ?",
                String.class, UUID.fromString(candidate2)
        )).isEqualTo("SUPERSEDED");

        // Execute with previewHash captured before identifiers change → 409, no merge row
        String loser5 = createContact(ownerAccess, tenantA, "Loser5", "stale.exec@example.com", "+919900000411");
        mockMvc.perform(post("/api/v1/app/contacts/" + loser5 + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        String survivor5 = createContact(ownerAccess, tenantA, "Survivor5", "stale.exec@example.com", "+919900000412");
        String candidate5 = objectMapper.readTree(mockMvc.perform(get("/api/v1/app/contact-duplicates")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .param("contactId", survivor5))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("items").get(0).get("id").asText();
        String hash5 = objectMapper.readTree(mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidate5,
                                "survivorContactId", survivor5,
                                "loserContactId", loser5
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("previewHash").asText();
        jdbcTemplate.update(
                "DELETE FROM contact_channels WHERE contact_id = ? AND channel_type = 'EMAIL'",
                UUID.fromString(loser5)
        );
        mockMvc.perform(post("/api/v1/app/contacts/merges")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", "stale-exec-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidate5,
                                "survivorContactId", survivor5,
                                "loserContactId", loser5,
                                "previewHash", hash5,
                                "confirm", true
                        ))))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE loser_contact_id = ?",
                Integer.class, UUID.fromString(loser5)
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT merged_into_contact_id FROM contacts WHERE id = ?",
                UUID.class, UUID.fromString(loser5)
        )).isNull();

        // Linked-user conflict
        String loser3 = createContact(ownerAccess, tenantA, "Loser3", "link.merge@example.com", "+919900000211");
        mockMvc.perform(post("/api/v1/app/contacts/" + loser3 + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        String survivor3 = createContact(ownerAccess, tenantA, "Survivor3", "link.merge@example.com", "+919900000212");
        String candidate3 = objectMapper.readTree(mockMvc.perform(get("/api/v1/app/contact-duplicates")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .param("contactId", survivor3))
                .andReturn().getResponse().getContentAsString()).get("items").get(0).get("id").asText();
        UUID otherUser = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'member-m42@example.com'", UUID.class);
        UUID ownerUser = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'owner-m42@example.com'", UUID.class);
        jdbcTemplate.update("UPDATE contacts SET linked_customer_user_id = ? WHERE id = ?",
                ownerUser, UUID.fromString(survivor3));
        jdbcTemplate.update("UPDATE contacts SET linked_customer_user_id = ? WHERE id = ?",
                otherUser, UUID.fromString(loser3));
        mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidate3,
                                "survivorContactId", survivor3,
                                "loserContactId", loser3
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeAllowed").value(false))
                .andExpect(jsonPath("$.blockingConflicts[0].code").value("LINKED_USER_CONFLICT"));

        // Concurrent identical requests → one merge
        String loser4 = createContact(ownerAccess, tenantA, "Loser4", "conc.merge@example.com", "+919900000311");
        mockMvc.perform(post("/api/v1/app/contacts/" + loser4 + "/archive")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId()))
                .andExpect(status().isOk());
        String survivor4 = createContact(ownerAccess, tenantA, "Survivor4", "conc.merge@example.com", "+919900000312");
        String candidate4 = objectMapper.readTree(mockMvc.perform(get("/api/v1/app/contact-duplicates")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .param("contactId", survivor4))
                .andReturn().getResponse().getContentAsString()).get("items").get(0).get("id").asText();
        String hash4 = objectMapper.readTree(mockMvc.perform(post("/api/v1/app/contacts/merges/preview")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidate4,
                                "survivorContactId", survivor4,
                                "loserContactId", loser4
                        ))))
                .andReturn().getResponse().getContentAsString()).get("previewHash").asText();
        String concKey = "merge-conc-" + UUID.randomUUID();
        Map<String, Object> concBody = Map.of(
                "candidateId", candidate4,
                "survivorContactId", survivor4,
                "loserContactId", loser4,
                "previewHash", hash4,
                "confirm", true
        );
        // Identical idempotent key resolves to one merge; a second key cannot rematch the same loser.
        mockMvc.perform(post("/api/v1/app/contacts/merges")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", concKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(concBody)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/app/contacts/merges")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", concKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(concBody)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/app/contacts/merges")
                        .header("Authorization", "Bearer " + ownerAccess)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .header("Idempotency-Key", "merge-again-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(concBody)))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contact_merges WHERE loser_contact_id = ?",
                Integer.class, UUID.fromString(loser4)
        )).isEqualTo(1);

        // Append-only still enforced
        UUID eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM contact_consent_events WHERE contact_id = ? LIMIT 1",
                UUID.class, UUID.fromString(survivorId)
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM contact_consent_events WHERE id = ?", eventId)
        ).hasMessageContaining("append-only");
    }

    private void grantConsent(String access, TenantPair tenant, String contactId, String channel, String purpose, String status)
            throws Exception {
        mockMvc.perform(put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channelType", channel,
                                "purpose", purpose,
                                "status", status,
                                "source", "API",
                                "evidenceNote", "test"
                        ))))
                .andExpect(status().isOk());
    }

    private String createStaticGroup(String access, TenantPair tenant, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug,
                                "slug", slug + "-" + UUID.randomUUID().toString().substring(0, 8),
                                "groupType", "STATIC"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createDynamicGroup(String access, TenantPair tenant, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/contact-groups")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug,
                                "slug", slug + "-" + UUID.randomUUID().toString().substring(0, 8),
                                "groupType", "DYNAMIC",
                                "filterDefinition", Map.of(
                                        "version", 1,
                                        "match", "ALL",
                                        "conditions", List.of(Map.of("field", "status", "op", "eq", "value", "ACTIVE"))
                                )
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

    private UUID seedTenantViewerRole() {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name, scope, description) VALUES (?, ?, 'Viewer', 'TENANT', 'Test')",
                roleId, "TENANT_VIEWER_M42_" + roleId.toString().substring(0, 8)
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
