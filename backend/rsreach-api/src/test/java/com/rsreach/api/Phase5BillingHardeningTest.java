package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.PaymentService;
import com.rsreach.billing.SubscriptionService;
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
 * Phase 5 M2 — billing hardening integration tests.
 *
 * <h3>Covers</h3>
 * <ol>
 *   <li>Webhook happy path: {@code mock-valid} signature + {@code payment.captured} payload
 *       containing tenant_id in notes → 200; direct domain call asserts CAPTURED + ACTIVE.
 *   <li>Duplicate webhook same event id → 200 idempotent (no double-processing).
 *   <li>Cross-tenant invoice isolation: tenant A's token cannot retrieve tenant B's invoice → 404.
 * </ol>
 *
 * <p>Runs only when Docker is available (Testcontainers PostgreSQL). Skipped on CI without Docker.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=local",
        "rsreach.security.expose-dev-tokens=false",
        "rsreach.jwt.customer.secret=test-customer-jwt-secret-p5harden",
        "rsreach.jwt.staff.secret=test-staff-jwt-secret-p5harden!!!",
        "rsreach.secrets.master-key=test-master-key-material-p5harden",
        "rsreach.bootstrap.staff-email=owner@billing-p5.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase5BillingHardeningTest#dockerAvailable")
class Phase5BillingHardeningTest {

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
    @Autowired private PaymentService paymentService;
    @Autowired private SubscriptionService subscriptionService;

    // -----------------------------------------------------------------------
    // Test 1: Webhook happy path — mock-valid signature + payment.captured
    // -----------------------------------------------------------------------

    /**
     * On the local profile, the Razorpay gateway accepts the sentinel {@code mock-valid} as a valid
     * signature when the webhook secret is blank. This test:
     * <ol>
     *   <li>Registers a customer, creates a tenant, and activates the free plan.
     *   <li>Uses a test-only JDBC UPDATE to set the plan version price to 100 paise so that
     *       {@link PaymentService#applyRazorpayPayment} can run against a priced plan context.
     *   <li>POSTs a {@code payment.captured} webhook with {@code mock-valid} signature → 200.
     *   <li>Calls {@code paymentService.applyRazorpayPayment} directly (simulating the worker
     *       processing the outbox event) and asserts payment CAPTURED and subscription ACTIVE.
     * </ol>
     */
    @Test
    void webhookPaymentCapturedHappyPath() throws Exception {
        // 1. Register + create tenant
        JsonNode tokens = registerCustomer("p5-webhook@example.com", "P5 Webhook", "pass1234");
        String access = tokens.get("accessToken").asText();
        TenantPair tenant = createTenant(access, "P5Webhook-" + UUID.randomUUID());

        // 2. Activate free plan → subscription exists
        mockMvc.perform(post("/api/v1/app/billing/activate-free")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 3. Test-only: set plan version price to 100 paise so applyRazorpayPayment
        //    can operate in a context that has a priced subscription.
        //    This is an in-test JDBC UPDATE only; it does NOT modify seeded plan catalog data.
        UUID tenantUUID = UUID.fromString(tenant.tenantId());
        UUID planVersionId = jdbcTemplate.queryForObject(
                "SELECT plan_version_id FROM tenant_subscription WHERE tenant_id = ?",
                UUID.class, tenantUUID);
        assertThat(planVersionId).isNotNull();
        jdbcTemplate.update("UPDATE plan_version SET price_paise = 100 WHERE id = ?", planVersionId);

        // 4. POST webhook with mock-valid signature
        String providerPaymentId = "pay_p5_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String eventId = "evt_p5_hp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String webhookBody = buildPaymentCapturedPayload(eventId, providerPaymentId, tenant.tenantId(), 100L);

        mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "mock-valid")
                        .content(webhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.eventId").isString());

        // 5. Simulate worker: call applyRazorpayPayment directly (the outbox job would do this)
        BillingModels.PaymentView payment = paymentService.applyRazorpayPayment(
                tenantUUID, providerPaymentId, "order_test_p5_001", 100L);
        assertThat(payment.status()).isEqualTo("CAPTURED");
        assertThat(payment.amountPaise()).isEqualTo(100L);
        assertThat(payment.provider()).isEqualTo("razorpay");

        // 6. Subscription must be ACTIVE
        BillingModels.SubscriptionView sub = subscriptionService.getSubscription(tenantUUID)
                .orElseThrow(() -> new AssertionError("No subscription found"));
        assertThat(sub.status()).isEqualTo(BillingModels.STATUS_ACTIVE);
    }

    // -----------------------------------------------------------------------
    // Test 2: Duplicate webhook event id is idempotent
    // -----------------------------------------------------------------------

    /**
     * Posting the same Razorpay webhook event id twice must return 200 both times and
     * must not create a duplicate payment or processing record.
     */
    @Test
    void duplicateWebhookEventIdIsIdempotent() throws Exception {
        // Register + create tenant
        JsonNode tokens = registerCustomer("p5-dedup@example.com", "P5 Dedup", "pass1234");
        String access = tokens.get("accessToken").asText();
        TenantPair tenant = createTenant(access, "P5Dedup-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/app/billing/activate-free")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId()))
                .andExpect(status().isOk());

        String providerPaymentId = "pay_dedup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String eventId = "evt_p5_dedup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String webhookBody = buildPaymentCapturedPayload(eventId, providerPaymentId, tenant.tenantId(), 500L);

        // First POST → 200
        MvcResult first = mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "mock-valid")
                        .content(webhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andReturn();

        String firstEventId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("eventId").asText();

        // Second POST with same event id → 200 (idempotent)
        MvcResult second = mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "mock-valid")
                        .content(webhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andReturn();

        String secondEventId = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("eventId").asText();

        // Both calls return the same webhook event id — the second was a no-op
        assertThat(secondEventId).isEqualTo(firstEventId);

        // Only one provider_webhook_events row for this event
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM provider_webhook_events WHERE provider_event_id = ?",
                Integer.class, eventId);
        assertThat(rowCount).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Test 3: Cross-tenant invoice isolation — tenant A cannot read tenant B's invoice
    // -----------------------------------------------------------------------

    /**
     * Creates an invoice for tenant B via JDBC, then verifies that tenant A's token
     * receives 404 when attempting to retrieve that invoice id.
     * This proves the {@code WHERE id = ? AND tenant_id = ?} guard in InvoiceService.
     */
    @Test
    void crossTenantInvoiceIsolation404() throws Exception {
        // Tenant A
        JsonNode tokensA = registerCustomer("p5-iso-a@example.com", "P5 Iso A", "pass1234");
        String accessA = tokensA.get("accessToken").asText();
        TenantPair tenantA = createTenant(accessA, "P5IsoA-" + UUID.randomUUID());

        // Tenant B (separate customer account)
        JsonNode tokensB = registerCustomer("p5-iso-b@example.com", "P5 Iso B", "pass1234");
        String accessB = tokensB.get("accessToken").asText();
        TenantPair tenantB = createTenant(accessB, "P5IsoB-" + UUID.randomUUID());

        // Activate free for both (needed for TENANT_BILLING_READ permission resolution)
        mockMvc.perform(post("/api/v1/app/billing/activate-free")
                        .header("Authorization", "Bearer " + accessA)
                        .header("X-Tenant-Id", tenantA.tenantId()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/app/billing/activate-free")
                        .header("Authorization", "Bearer " + accessB)
                        .header("X-Tenant-Id", tenantB.tenantId()))
                .andExpect(status().isOk());

        // Insert an invoice directly for tenant B via JDBC
        UUID invoiceBId = UUID.randomUUID();
        UUID tenantBUUID = UUID.fromString(tenantB.tenantId());
        jdbcTemplate.update(
                """
                INSERT INTO billing_invoice (
                    id, tenant_id, invoice_number, status, currency,
                    subtotal_paise, tax_paise, total_paise,
                    cgst_paise, sgst_paise, igst_paise,
                    issued_at, due_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'ISSUED', 'INR', 50000, 0, 50000, 0, 0, 0,
                          NOW(), NOW() + INTERVAL '30 days', NOW(), NOW())
                """,
                invoiceBId, tenantBUUID, "INV-ISOLATION-TEST-B"
        );

        // Tenant A tries to GET tenant B's invoice → must get 404 (tenant_id mismatch)
        mockMvc.perform(get("/api/v1/app/billing/invoices/" + invoiceBId)
                        .header("Authorization", "Bearer " + accessA)
                        .header("X-Tenant-Id", tenantA.tenantId()))
                .andExpect(status().isNotFound());

        // Sanity: tenant B can read its own invoice
        mockMvc.perform(get("/api/v1/app/billing/invoices/" + invoiceBId)
                        .header("Authorization", "Bearer " + accessB)
                        .header("X-Tenant-Id", tenantB.tenantId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invoiceBId.toString()));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String buildPaymentCapturedPayload(
            String eventId, String providerPaymentId, String tenantId, long amountPaise) {
        return String.format(
                """
                {
                  "id": "%s",
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "order_id": "order_test_p5_mock",
                        "amount": %d,
                        "notes": {"tenant_id": "%s"}
                      }
                    }
                  }
                }
                """,
                eventId, providerPaymentId, amountPaise, tenantId);
    }

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
