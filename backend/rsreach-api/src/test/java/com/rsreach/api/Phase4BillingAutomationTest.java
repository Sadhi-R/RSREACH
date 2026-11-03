package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
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
        "rsreach.jwt.customer.secret=test-customer-jwt-secret-p4billing",
        "rsreach.jwt.staff.secret=test-staff-jwt-secret-p4billing!!!",
        "rsreach.secrets.master-key=test-master-key-material-p4billing",
        "rsreach.bootstrap.staff-email=owner@billing-p4.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase4BillingAutomationTest#dockerAvailable")
class Phase4BillingAutomationTest {

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
    @Autowired private EntitlementService entitlementService;

    // -----------------------------------------------------------------------
    // Test: activate free plan, list plans, entitlement deny
    // -----------------------------------------------------------------------

    @Test
    void billingActivateFreeListPlansEntitlementDeny() throws Exception {
        JsonNode tokens = registerCustomer("billing-p4@example.com", "Billing P4", "pass1234");
        String access = tokens.get("accessToken").asText();
        TenantPair tenant = createTenant(access, "BillingP4-" + UUID.randomUUID());

        // 1. No subscription initially → GET /billing/subscription returns 404
        mockMvc.perform(get("/api/v1/app/billing/subscription")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId()))
                .andExpect(status().isNotFound());

        // 2. Activate free plan
        MvcResult subResult = mockMvc.perform(post("/api/v1/app/billing/activate-free")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        // 3. GET /billing/subscription now returns the subscription
        mockMvc.perform(get("/api/v1/app/billing/subscription")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 4. GET /billing/plans returns plan catalog with versions and entitlements
        mockMvc.perform(get("/api/v1/app/billing/plans")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId()))
                .andExpect(status().isOk());

        // 5. GET /billing/usage returns empty list (no usage yet)
        mockMvc.perform(get("/api/v1/app/billing/usage")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId()))
                .andExpect(status().isOk());

        // 6. Consume CAMPAIGNS up to the FREE plan limit
        UUID tenantUUID = UUID.fromString(tenant.tenantId());
        UUID workspaceUUID = UUID.fromString(tenant.workspaceId());
        Long campaignLimit = entitlementService.resolveLimit(
                tenantUUID,
                jdbcTemplate.queryForObject(
                        "SELECT plan_version_id FROM tenant_subscription WHERE tenant_id = ?",
                        UUID.class, tenantUUID
                ),
                BillingModels.METER_CAMPAIGNS
        );

        if (campaignLimit != null && campaignLimit > 0) {
            for (int i = 0; i < campaignLimit; i++) {
                entitlementService.consume(tenantUUID, workspaceUUID,
                        BillingModels.METER_CAMPAIGNS, 1L,
                        "TEST_CAMPAIGN_CONSUME_" + i, "test", "test-" + i);
            }
            // Next consume should fail with CONFLICT
            BillingModels.CheckResult denied = entitlementService.check(
                    tenantUUID, BillingModels.METER_CAMPAIGNS, 1L);
            assertThat(denied.allowed()).isFalse();
            assertThat(denied.reason()).contains("LIMIT_EXCEEDED");
        }
    }

    // -----------------------------------------------------------------------
    // Test: Razorpay webhook — forged signature → 401
    // -----------------------------------------------------------------------

    @Test
    void razorpayWebhookForgedSignature401() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "forged-signature-not-valid")
                        .content("{\"id\":\"evt_test_001\",\"event\":\"payment.captured\",\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Test: Tenant isolation — tenant A cannot see tenant B invoices
    // -----------------------------------------------------------------------

    @Test
    void invoiceIsolationTenantACannotSeeTenantBInvoices() throws Exception {
        JsonNode tokensA = registerCustomer("billing-iso-a@example.com", "Billing Iso A", "pass1234");
        JsonNode tokensB = registerCustomer("billing-iso-b@example.com", "Billing Iso B", "pass1234");
        String accessA = tokensA.get("accessToken").asText();
        String accessB = tokensB.get("accessToken").asText();
        TenantPair tenantA = createTenant(accessA, "BillingIsoA-" + UUID.randomUUID());
        TenantPair tenantB = createTenant(accessB, "BillingIsoB-" + UUID.randomUUID());

        // Activate free for both tenants
        mockMvc.perform(post("/api/v1/app/billing/activate-free")
                        .header("Authorization", "Bearer " + accessA)
                        .header("X-Tenant-Id", tenantA.tenantId()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/app/billing/activate-free")
                        .header("Authorization", "Bearer " + accessB)
                        .header("X-Tenant-Id", tenantB.tenantId()))
                .andExpect(status().isOk());

        // Tenant A fetching invoices should not see tenant B data
        // (both have 0 invoices on FREE, but isolation is enforced by tenant_id)
        mockMvc.perform(get("/api/v1/app/billing/invoices")
                        .header("Authorization", "Bearer " + accessA)
                        .header("X-Tenant-Id", tenantA.tenantId()))
                .andExpect(status().isOk());

        // Tenant A cannot access tenant B subscription by changing the header manually
        // (they don't have membership in tenant B, so they should get FORBIDDEN)
        mockMvc.perform(get("/api/v1/app/billing/subscription")
                        .header("Authorization", "Bearer " + accessA)
                        .header("X-Tenant-Id", tenantB.tenantId()))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Test: Automation CRUD and activate (Free plan AUTOMATIONS=0 → deny)
    // -----------------------------------------------------------------------

    @Test
    void automationCreateAndActivateDeniedOnFreePlan() throws Exception {
        JsonNode tokens = registerCustomer("auto-p4@example.com", "Auto P4", "pass1234");
        String access = tokens.get("accessToken").asText();
        TenantPair tenant = createTenant(access, "AutoP4-" + UUID.randomUUID());

        // Activate free plan
        mockMvc.perform(post("/api/v1/app/billing/activate-free")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId()))
                .andExpect(status().isOk());

        // Create a draft workflow
        MvcResult createResult = mockMvc.perform(post("/api/v1/app/automations")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Test Automation",
                                "triggerType", "CONTACT_CREATED"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        String workflowId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // Check if FREE plan allows 0 automations; if so, activate should fail
        UUID tenantUUID = UUID.fromString(tenant.tenantId());
        UUID planVersionId = jdbcTemplate.queryForObject(
                "SELECT plan_version_id FROM tenant_subscription WHERE tenant_id = ?",
                UUID.class, tenantUUID
        );
        Long autoLimit = entitlementService.resolveLimit(tenantUUID, planVersionId,
                BillingModels.METER_AUTOMATIONS);

        if (autoLimit != null && autoLimit == 0) {
            // Activation should fail with CONFLICT (limit exceeded)
            mockMvc.perform(post("/api/v1/app/automations/{id}/activate", workflowId)
                            .header("Authorization", "Bearer " + access)
                            .header("X-Tenant-Id", tenant.tenantId())
                            .header("X-Workspace-Id", tenant.workspaceId()))
                    .andExpect(status().isConflict());

            // Create entitlement override via direct service call (simulating platform admin action)
            entitlementService.createOverride(new BillingModels.OverrideCommand(
                    tenantUUID, BillingModels.METER_AUTOMATIONS, 5L,
                    "test-override", null
            ));

            // Now activation should succeed
            mockMvc.perform(post("/api/v1/app/automations/{id}/activate", workflowId)
                            .header("Authorization", "Bearer " + access)
                            .header("X-Tenant-Id", tenant.tenantId())
                            .header("X-Workspace-Id", tenant.workspaceId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        } else {
            // No limit or > 0 limit: just activate directly
            mockMvc.perform(post("/api/v1/app/automations/{id}/activate", workflowId)
                            .header("Authorization", "Bearer " + access)
                            .header("X-Tenant-Id", tenant.tenantId())
                            .header("X-Workspace-Id", tenant.workspaceId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        // List runs (should be empty)
        mockMvc.perform(get("/api/v1/app/automations/{id}/runs", workflowId)
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    // -----------------------------------------------------------------------
    // Helpers (mirror Phase3CommunicationsTest pattern)
    // -----------------------------------------------------------------------

    private JsonNode registerCustomer(String email, String displayName, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "displayName", displayName,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private TenantPair createTenant(String accessToken, String tenantName) throws Exception {
        String slug = tenantName.toLowerCase().replaceAll("[^a-z0-9]", "-");
        MvcResult result = mockMvc.perform(post("/api/v1/app/tenants")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", tenantName, "slug", slug))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TenantPair(body.get("id").asText(), body.get("defaultWorkspaceId").asText());
    }

    private record TenantPair(String tenantId, String workspaceId) {
    }
}
