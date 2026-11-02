package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.security.auth.TokenHasher;
import com.rsreach.tenancy.InvitationRolePolicy;
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

/**
 * Regression coverage for Phase 1 security remediation:
 * scoped authorization, invitation role allowlists, and token non-leakage.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=local",
        "rsreach.security.expose-dev-tokens=false",
        "rsreach.jwt.customer.secret=test-customer-jwt-secret-value-32b",
        "rsreach.jwt.staff.secret=test-staff-jwt-secret-value-32b!!!",
        "rsreach.secrets.master-key=test-master-key-material",
        "rsreach.bootstrap.staff-email=owner@security-test.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase1AuthorizationSecurityTest#dockerAvailable")
class Phase1AuthorizationSecurityTest {

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
    void scopedPermissionsInvitationRolesAndTokenNonLeakage() throws Exception {
        JsonNode userTokens = registerCustomer("scoped@example.com", "Scoped User", "password123");
        String access = userTokens.get("accessToken").asText();

        // Tenant A (owner) and Tenant B (low-privilege viewer role)
        TenantPair tenantA = createTenant(access, "TenantA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(access, "TenantB-" + UUID.randomUUID());

        UUID viewerRoleId = seedTenantViewerRole();
        jdbcTemplate.update(
                "UPDATE tenant_memberships SET role_id = ? WHERE tenant_id = ? AND user_id = ("
                        + "SELECT id FROM customer_users WHERE email_normalized = 'scoped@example.com')",
                viewerRoleId, UUID.fromString(tenantB.tenantId())
        );

        // 1-2: Owner perms in A do not apply in B
        mockMvc.perform(post("/api/v1/app/tenants/" + tenantA.tenantId() + "/invitations")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invite-a@example.com\",\"roleCode\":\"TENANT_ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.devInviteToken").doesNotExist());

        mockMvc.perform(post("/api/v1/app/tenants/" + tenantB.tenantId() + "/invitations")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantB.tenantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invite-b@example.com\",\"roleCode\":\"TENANT_ADMIN\"}"))
                .andExpect(status().isForbidden());

        // Forged header: Tenant A context while inviting into Tenant B path still fails (path id wins)
        mockMvc.perform(post("/api/v1/app/tenants/" + tenantB.tenantId() + "/invitations")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"escalate@example.com\",\"roleCode\":\"TENANT_ADMIN\"}"))
                .andExpect(status().isForbidden());

        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'scoped@example.com'", UUID.class);

        // Invitation allowlists / platform rejection while still Tenant A owner
        for (String role : InvitationRolePolicy.ALLOWED_TENANT_ROLES) {
            mockMvc.perform(post("/api/v1/app/tenants/" + tenantA.tenantId() + "/invitations")
                            .header("Authorization", "Bearer " + access)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"ok-" + role.toLowerCase() + "@example.com\",\"roleCode\":\"" + role + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.devInviteToken").doesNotExist());
        }
        for (String role : InvitationRolePolicy.ALLOWED_WORKSPACE_ROLES) {
            mockMvc.perform(post("/api/v1/app/tenants/" + tenantA.tenantId() + "/invitations")
                            .header("Authorization", "Bearer " + access)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"ws-" + role.toLowerCase() + "@example.com\",\"roleCode\":\"" + role + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        for (String role : InvitationRolePolicy.FORBIDDEN_PLATFORM_ROLES) {
            mockMvc.perform(post("/api/v1/app/tenants/" + tenantA.tenantId() + "/invitations")
                            .header("Authorization", "Bearer " + access)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"plat-" + role.toLowerCase() + "@example.com\",\"roleCode\":\"" + role + "\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/app/tenants/" + tenantA.tenantId() + "/invitations")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.com\",\"roleCode\":\"NOT_A_REAL_ROLE\"}"))
                .andExpect(status().isBadRequest());

        // Create second workspace while owner, then invite/accept assignee and assign workspace roles
        MvcResult wsBResult = mockMvc.perform(post("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Workspace B\",\"slug\":\"ws-b-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String workspaceBId = objectMapper.readTree(wsBResult.getResponse().getContentAsString()).get("id").asText();

        JsonNode assigneeTokens = registerCustomer("assignee@example.com", "Assignee", "password123");
        String inviteToken = createInviteWithKnownToken(tenantA.tenantId(), userId, "assignee@example.com", "TENANT_ADMIN");
        mockMvc.perform(post("/api/v1/app/invitations/accept")
                        .header("Authorization", "Bearer " + assigneeTokens.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + inviteToken + "\"}"))
                .andExpect(status().isOk());

        UUID assigneeId = jdbcTemplate.queryForObject(
                "SELECT id FROM customer_users WHERE email_normalized = 'assignee@example.com'", UUID.class);
        for (String wsRole : InvitationRolePolicy.ALLOWED_WORKSPACE_ROLES) {
            mockMvc.perform(post("/api/v1/app/workspaces/members/role")
                            .header("Authorization", "Bearer " + access)
                            .header("X-Tenant-Id", tenantA.tenantId())
                            .header("X-Workspace-Id", tenantA.workspaceId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "userId", assigneeId.toString(),
                                    "roleCode", wsRole
                            ))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/app/workspaces/members/role")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", assigneeId.toString(),
                                "roleCode", "TENANT_ADMIN"
                        ))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/app/workspaces/members/role")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", assigneeId.toString(),
                                "roleCode", "PLATFORM_OWNER"
                        ))))
                .andExpect(status().isForbidden());

        // Expired / revoked / tampered acceptance
        String revokedToken = createInviteWithKnownToken(tenantA.tenantId(), userId, "revoked@example.com", "TENANT_ADMIN");
        UUID revokedInviteId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenant_invitations WHERE email_normalized = 'revoked@example.com'", UUID.class);
        jdbcTemplate.update("UPDATE tenant_invitations SET revoked_at = NOW() WHERE id = ?", revokedInviteId);
        JsonNode revokedUser = registerCustomer("revoked@example.com", "Revoked", "password123");
        mockMvc.perform(post("/api/v1/app/invitations/accept")
                        .header("Authorization", "Bearer " + revokedUser.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + revokedToken + "\"}"))
                .andExpect(status().isUnauthorized());

        String expiredToken = createInviteWithKnownToken(tenantA.tenantId(), userId, "expired@example.com", "TENANT_ADMIN");
        jdbcTemplate.update(
                "UPDATE tenant_invitations SET expires_at = NOW() - INTERVAL '1 hour' WHERE email_normalized = 'expired@example.com'");
        JsonNode expiredUser = registerCustomer("expired@example.com", "Expired", "password123");
        mockMvc.perform(post("/api/v1/app/invitations/accept")
                        .header("Authorization", "Bearer " + expiredUser.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + expiredToken + "\"}"))
                .andExpect(status().isUnauthorized());

        String goodToken = createInviteWithKnownToken(tenantA.tenantId(), userId, "tamper@example.com", "TENANT_ADMIN");
        JsonNode wrongUser = registerCustomer("wrong-email@example.com", "Wrong", "password123");
        mockMvc.perform(post("/api/v1/app/invitations/accept")
                        .header("Authorization", "Bearer " + wrongUser.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + goodToken + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/app/invitations/accept")
                        .header("Authorization", "Bearer " + wrongUser.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"definitely-not-a-real-token-value\"}"))
                .andExpect(status().isUnauthorized());

        // Demote tenant-level role on A so workspace inheritance cannot leak org.manage across workspaces
        UUID wsAdminRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = 'WORKSPACE_ADMIN'", UUID.class);
        UUID memberRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = 'WORKSPACE_MEMBER'", UUID.class);
        jdbcTemplate.update(
                "UPDATE tenant_memberships SET role_id = ? WHERE tenant_id = ? AND user_id = ?",
                viewerRoleId, UUID.fromString(tenantA.tenantId()), userId
        );
        jdbcTemplate.update(
                "UPDATE workspace_memberships SET role_id = ? WHERE tenant_id = ? AND workspace_id = ? AND user_id = ?",
                wsAdminRoleId, UUID.fromString(tenantA.tenantId()), UUID.fromString(tenantA.workspaceId()), userId
        );
        jdbcTemplate.update(
                "UPDATE workspace_memberships SET role_id = ? WHERE tenant_id = ? AND workspace_id = ? AND user_id = ?",
                memberRoleId, UUID.fromString(tenantA.tenantId()), UUID.fromString(workspaceBId), userId
        );

        // 3-4: Workspace A permissions do not apply in Workspace B
        mockMvc.perform(post("/api/v1/app/departments")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", tenantA.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dept A\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/app/departments")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .header("X-Workspace-Id", workspaceBId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dept B\"}"))
                .andExpect(status().isForbidden());

        // 5: Missing tenant/workspace context rejected for scoped ops
        mockMvc.perform(post("/api/v1/app/departments")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No Context\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden());

        // 6: Invalid/disabled membership rejected
        jdbcTemplate.update(
                "UPDATE tenant_memberships SET status = 'DISABLED' WHERE tenant_id = ? AND user_id = ?",
                UUID.fromString(tenantB.tenantId()), userId
        );
        mockMvc.perform(get("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantB.tenantId()))
                .andExpect(status().isForbidden());

        // 7: Changing headers cannot escalate into foreign tenant
        JsonNode other = registerCustomer("other@example.com", "Other", "password123");
        TenantPair otherTenant = createTenant(other.get("accessToken").asText(), "OtherOrg-" + UUID.randomUUID());
        mockMvc.perform(post("/api/v1/app/tenants/" + otherTenant.tenantId() + "/invitations")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenantA.tenantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"roleCode\":\"TENANT_ADMIN\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", otherTenant.tenantId()))
                .andExpect(status().isForbidden());

        // 8: Staff realm permissions remain separate
        mockMvc.perform(get("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());

        MvcResult staffLogin = mockMvc.perform(post("/api/v1/platform/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@security-test.local\",\"password\":\"OwnerPass123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode mfa = objectMapper.readTree(staffLogin.getResponse().getContentAsString());
        String staffAccess = completeStaffMfa(mfa);
        mockMvc.perform(get("/api/v1/platform/tenants").header("Authorization", "Bearer " + staffAccess))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/app/tenants").header("Authorization", "Bearer " + staffAccess))
                .andExpect(status().isUnauthorized());

        // Password reset responses never leak raw tokens by default; unknown emails match known shape
        MvcResult resetResult = mockMvc.perform(post("/api/v1/app/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"scoped@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.devResetToken").doesNotExist())
                .andReturn();
        String resetBody = resetResult.getResponse().getContentAsString();
        assertThat(resetBody).doesNotContain("devResetToken");
        assertThat(resetBody).doesNotContain("devInviteToken");

        MvcResult unknownReset = mockMvc.perform(post("/api/v1/app/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"does-not-exist@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.devResetToken").doesNotExist())
                .andReturn();
        assertThat(unknownReset.getResponse().getContentAsString()).isEqualTo(resetBody);

        Integer inviteAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action LIKE 'invitation.%'", Integer.class);
        assertThat(inviteAudits).isGreaterThan(0);
    }

    private UUID seedTenantViewerRole() {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name, scope, description) VALUES (?, 'TENANT_VIEWER', 'Viewer', 'TENANT', 'Test-only low privilege')",
                roleId
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

    private String createInviteWithKnownToken(String tenantId, UUID invitedBy, String email, String roleCode) {
        String raw = TokenHasher.newOpaqueToken();
        UUID roleId = jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, roleCode);
        jdbcTemplate.update(
                """
                INSERT INTO tenant_invitations
                    (id, tenant_id, email_normalized, role_id, token_hash, invited_by, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW() + INTERVAL '7 days', NOW())
                ON CONFLICT (tenant_id, email_normalized) DO UPDATE
                SET token_hash = EXCLUDED.token_hash,
                    role_id = EXCLUDED.role_id,
                    invited_by = EXCLUDED.invited_by,
                    expires_at = EXCLUDED.expires_at,
                    accepted_at = NULL,
                    revoked_at = NULL
                """,
                UUID.randomUUID(), UUID.fromString(tenantId), email.toLowerCase(), roleId,
                TokenHasher.sha256(raw), invitedBy
        );
        return raw;
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

    private String completeStaffMfa(JsonNode mfa) throws Exception {
        String mfaToken = mfa.get("mfaToken").asText();
        String enrollmentSecret = mfa.get("enrollmentSecret").asText();
        String code = generateTotp(enrollmentSecret);
        MvcResult staffTokensResult = mockMvc.perform(post("/api/v1/platform/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(staffTokensResult.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static String generateTotp(String base32Secret) throws Exception {
        long timestep = java.time.Instant.now().getEpochSecond() / 30;
        byte[] key = decodeBase32(base32Secret);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
        byte[] data = java.nio.ByteBuffer.allocate(8).putLong(timestep).array();
        byte[] hash = mac.doFinal(data);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        return String.format("%06d", binary % 1_000_000);
    }

    private static byte[] decodeBase32(String encoded) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String s = encoded.trim().toUpperCase().replace("=", "");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((s.length() * 5) / 8);
        int bitBuffer = 0;
        int bitsLeft = 0;
        for (char c : s.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) {
                continue;
            }
            bitBuffer = (bitBuffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                buffer.put((byte) ((bitBuffer >> (bitsLeft - 8)) & 0xFF));
                bitsLeft -= 8;
            }
        }
        byte[] out = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(out);
        return out;
    }

    private record TenantPair(String tenantId, String workspaceId) {
    }
}
