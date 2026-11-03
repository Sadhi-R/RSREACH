package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
        "rsreach.bootstrap.staff-email=owner@webhook-m1.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!",
        "rsreach.whatsapp.verify-token=phase6-verify-token",
        "rsreach.webhooks.allow-mock-signatures=true"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.WebhookAccountBindingTest#dockerAvailable")
class WebhookAccountBindingTest {

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
    void metaCallbackResolvesAccountWithoutQueryTenantAndIgnoresForeignIds() throws Exception {
        String access = register("owner-m1-" + UUID.randomUUID() + "@example.com");
        TenantPair tenantA = createTenant(access, "HookA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(access, "HookB-" + UUID.randomUUID());
        String phoneA = phoneId();
        String phoneB = phoneId();
        connect(access, tenantA, "whatsapp", phoneA);
        connect(access, tenantB, "whatsapp", phoneB);

        String bodyA = metaBody(phoneA, "wamid-a", tenantB.tenantId());
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .param("tenantId", tenantB.tenantId())
                        .param("workspaceId", tenantB.workspaceId())
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyA.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(true));

        assertThat(storedTenant(phoneA)).isEqualTo(tenantA.tenantId());
        assertThat(storedWorkspace(phoneA)).isEqualTo(tenantA.workspaceId());

        String bodyB = metaBody(phoneB, "wamid-b", tenantA.tenantId());
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyB.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(true));
        assertThat(storedTenant(phoneB)).isEqualTo(tenantB.tenantId());
        assertThat(storedTenant(phoneA)).isEqualTo(tenantA.tenantId());
    }

    @Test
    void forgedSignatureDoesNotIngest() throws Exception {
        String phone = phoneId();
        String body = metaBody(phone, "wamid-forged", null);
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("invalid_signature"));
        assertThat(eventCount(phone)).isZero();
    }

    @Test
    void unknownPhoneNumberIsRejectedWithoutARow() throws Exception {
        String phone = phoneId();
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metaBody(phone, "wamid-unknown", null).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("account_not_found"));
        assertThat(eventCount(phone)).isZero();
    }

    @Test
    void missingPhoneNumberIdIsRejected() throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"waba-only","changes":[{"field":"messages","value":{}}]}]}
                """;
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("missing_account_ref"));
    }

    @Test
    void ambiguousAccountsAreRejected() throws Exception {
        String access = register("amb-" + UUID.randomUUID() + "@example.com");
        TenantPair tenantA = createTenant(access, "AmbA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(access, "AmbB-" + UUID.randomUUID());
        String phone = phoneId();
        connect(access, tenantA, "whatsapp", phone);
        connect(access, tenantB, "whatsapp", phone);
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metaBody(phone, "wamid-amb", null).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ambiguous_account"));
        assertThat(eventCount(phone)).isZero();
    }

    @Test
    void duplicateProviderEventIsIdempotent() throws Exception {
        String access = register("dup-" + UUID.randomUUID() + "@example.com");
        TenantPair tenant = createTenant(access, "Dup-" + UUID.randomUUID());
        String phone = phoneId();
        connect(access, tenant, "whatsapp", phone);
        byte[] body = metaBody(phone, "wamid-dup", null).getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(true));
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(false));
        assertThat(eventCount(phone)).isEqualTo(1L);
    }

    @Test
    void disconnectedAccountIsNotATenantFallback() throws Exception {
        String access = register("disc-" + UUID.randomUUID() + "@example.com");
        TenantPair tenant = createTenant(access, "Disc-" + UUID.randomUUID());
        String phone = phoneId();
        String accountId = connect(access, tenant, "whatsapp", phone);
        mockMvc.perform(delete("/api/v1/app/connected-accounts/" + accountId)
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .param("tenantId", tenant.tenantId())
                        .param("workspaceId", tenant.workspaceId())
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metaBody(phone, "wamid-disc", null).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("account_not_found"));
        assertThat(eventCount(phone)).isZero();
    }

    @Test
    void sendGridCallbackResolvesAccountRefFromBody() throws Exception {
        String access = register("sg-" + UUID.randomUUID() + "@example.com");
        TenantPair tenant = createTenant(access, "Sg-" + UUID.randomUUID());
        TenantPair other = createTenant(access, "SgOther-" + UUID.randomUUID());
        String accountRef = "sg-" + UUID.randomUUID();
        connect(access, tenant, "sendgrid", accountRef);
        String body = """
                [{"email":"person@example.com","event":"delivered","sg_message_id":"sg-msg-1","account_ref":"%s"}]
                """.formatted(accountRef);
        mockMvc.perform(post("/api/v1/webhooks/sendgrid")
                        .param("tenantId", other.tenantId())
                        .param("workspaceId", other.workspaceId())
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(true));
        assertThat(storedTenant(accountRef)).isEqualTo(tenant.tenantId());
    }

    @Test
    void verifyHandshakeReturnsChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "phase6-verify-token")
                        .param("hub.challenge", "challenge-ok"))
                .andExpect(status().isOk())
                .andExpect(content().string("challenge-ok"));
        mockMvc.perform(get("/api/v1/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong-token")
                        .param("hub.challenge", "challenge-ok"))
                .andExpect(status().isForbidden());
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Webhook Owner","password":"password123"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
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

    private String connect(String access, TenantPair tenant, String provider, String externalRef) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/connected-accounts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"%s","displayName":"Bound","accessToken":"token","ownershipMode":"CUSTOMER","externalAccountRef":"%s"}
                                """.formatted(provider, externalRef)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static String metaBody(String phoneId, String messageId, String ignoredTenantId) {
        String tenantField = ignoredTenantId == null ? "" : "\"tenantId\":\"" + ignoredTenantId + "\",";
        return """
                {%s"object":"whatsapp_business_account","entry":[{"id":"waba-1","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550001111","phone_number_id":"%s"},"messages":[{"from":"15550002222","id":"%s","type":"text","text":{"body":"hello"}}]}}]}]}
                """.formatted(tenantField, phoneId, messageId);
    }

    private String storedTenant(String accountRef) {
        return jdbcTemplate.queryForObject(
                "SELECT tenant_id::text FROM provider_webhook_events WHERE connected_account_ref = ?",
                String.class,
                accountRef
        );
    }

    private String storedWorkspace(String accountRef) {
        return jdbcTemplate.queryForObject(
                "SELECT workspace_id::text FROM provider_webhook_events WHERE connected_account_ref = ?",
                String.class,
                accountRef
        );
    }

    private long eventCount(String accountRef) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_webhook_events WHERE connected_account_ref = ?",
                Long.class,
                accountRef
        );
        return count == null ? 0L : count;
    }

    private static String phoneId() {
        return "pn" + UUID.randomUUID().toString().replace("-", "");
    }

    private record TenantPair(String tenantId, String workspaceId) {
    }
}
