package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
        "rsreach.jwt.customer.secret=test-customer-jwt-secret-value-32b",
        "rsreach.jwt.staff.secret=test-staff-jwt-secret-value-32b!!!",
        "rsreach.secrets.master-key=test-master-key-material",
        "rsreach.bootstrap.staff-email=owner@test.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase1IdentityTenancyTest#dockerAvailable")
class Phase1IdentityTenancyTest {

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
    void customerStaffAuthTenancyIsolationAndGrants() throws Exception {
        // Customer A register/login
        JsonNode aTokens = registerCustomer("a@example.com", "User A", "password123");
        String aAccess = aTokens.get("accessToken").asText();
        String aRefresh = aTokens.get("refreshToken").asText();

        mockMvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + aAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@example.com"));

        // Refresh rotation
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + aRefresh + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rotated = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        mockMvc.perform(post("/api/v1/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + aRefresh + "\"}"))
                .andExpect(status().isUnauthorized());

        // Create tenant
        MvcResult tenantResult = mockMvc.perform(post("/api/v1/app/tenants")
                        .header("Authorization", "Bearer " + rotated.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tenant = objectMapper.readTree(tenantResult.getResponse().getContentAsString());
        String tenantId = tenant.get("id").asText();
        String workspaceId = tenant.get("defaultWorkspaceId").asText();

        // Customer B cannot access tenant A
        JsonNode bTokens = registerCustomer("b@example.com", "User B", "password123");
        mockMvc.perform(get("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + bTokens.get("accessToken").asText())
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isForbidden());

        // Workspace isolation header validation for A
        mockMvc.perform(get("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + rotated.get("accessToken").asText())
                        .header("X-Tenant-Id", tenantId)
                        .header("X-Workspace-Id", workspaceId))
                .andExpect(status().isOk());

        UUID foreignWorkspace = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/app/workspaces")
                        .header("Authorization", "Bearer " + rotated.get("accessToken").asText())
                        .header("X-Tenant-Id", tenantId)
                        .header("X-Workspace-Id", foreignWorkspace.toString()))
                .andExpect(status().isForbidden());

        // Realm separation: customer token on platform
        mockMvc.perform(get("/api/v1/platform/me")
                        .header("Authorization", "Bearer " + rotated.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());

        // Staff login + MFA enrollment/verify
        MvcResult staffLogin = mockMvc.perform(post("/api/v1/platform/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@test.local\",\"password\":\"OwnerPass123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_REQUIRED"))
                .andReturn();
        JsonNode mfa = objectMapper.readTree(staffLogin.getResponse().getContentAsString());
        String mfaToken = mfa.get("mfaToken").asText();
        String enrollmentSecret = mfa.get("enrollmentSecret").asText();
        String code = generateTotp(enrollmentSecret);

        MvcResult staffTokensResult = mockMvc.perform(post("/api/v1/platform/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode staffTokens = objectMapper.readTree(staffTokensResult.getResponse().getContentAsString());
        String staffAccess = staffTokens.get("accessToken").asText();

        mockMvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + staffAccess))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/platform/tenants").header("Authorization", "Bearer " + staffAccess))
                .andExpect(status().isOk());

        // Staff grant request/approve/use/expiry
        MvcResult grantResult = mockMvc.perform(post("/api/v1/platform/grants")
                        .header("Authorization", "Bearer " + staffAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId,
                                "justification", "Support investigation",
                                "expiresAt", Instant.now().plusSeconds(3600).toString()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String grantId = objectMapper.readTree(grantResult.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/platform/grants/" + grantId + "/approve")
                        .header("Authorization", "Bearer " + staffAccess))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/platform/tenants/" + tenantId + "/secure")
                        .header("Authorization", "Bearer " + staffAccess))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE staff_access_grants SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?",
                UUID.fromString(grantId));
        mockMvc.perform(get("/api/v1/platform/tenants/" + tenantId + "/secure")
                        .header("Authorization", "Bearer " + staffAccess))
                .andExpect(status().isForbidden());

        Integer auditCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class);
        assertThat(auditCount).isGreaterThan(0);

        // Logout revokes refresh
        String staffRefresh = staffTokens.get("refreshToken").asText();
        mockMvc.perform(post("/api/v1/platform/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + staffRefresh + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/platform/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + staffRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
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

    private static String generateTotp(String base32Secret) throws Exception {
        // Mirror TotpService algorithm for test verification
        long timestep = Instant.now().getEpochSecond() / 30;
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
}
