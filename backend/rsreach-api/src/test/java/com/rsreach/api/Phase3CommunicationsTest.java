package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.communications.MessageSendWorker;
import com.rsreach.campaigns.CampaignService;
import java.nio.charset.StandardCharsets;
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
        "rsreach.bootstrap.staff-email=owner@comms-p3.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!",
        "rsreach.webhooks.allow-mock-signatures=true"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase3CommunicationsTest#dockerAvailable")
class Phase3CommunicationsTest {

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
    @Autowired private MessageSendWorker messageSendWorker;
    @Autowired private CampaignService campaignService;
    @Autowired private com.rsreach.email.MailboxSyncService mailboxSyncService;

    @Test
    void inboxInternalConversationWebhookCampaignEligibility() throws Exception {
        JsonNode ownerTokens = registerCustomer("owner-p3@example.com", "Owner P3", "password123");
        String access = ownerTokens.get("accessToken").asText();
        TenantPair tenant = createTenant(access, "CommsP3-" + UUID.randomUUID());

        MvcResult contactCreate = mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Inbox Person","channels":[
                                  {"channelType":"EMAIL","value":"inbox-p3@example.com","primary":true},
                                  {"channelType":"PHONE","value":"+15550001111","primary":true}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String contactId = objectMapper.readTree(contactCreate.getResponse().getContentAsString()).get("id").asText();

        MvcResult conversationCreate = mockMvc.perform(post("/api/v1/app/conversations")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "INTERNAL_TEST",
                                "contactId", contactId,
                                "subject", "Hello"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String conversationId = objectMapper.readTree(conversationCreate.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/api/v1/app/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bodyText":"Test outbound","purpose":"SERVICE","idempotencyKey":"p3-msg-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENT"));

        mockMvc.perform(get("/api/v1/app/conversations")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(conversationId));

        // Cross-tenant isolation
        JsonNode otherTokens = registerCustomer("other-p3@example.com", "Other P3", "password123");
        String otherAccess = otherTokens.get("accessToken").asText();
        TenantPair other = createTenant(otherAccess, "OtherComms-" + UUID.randomUUID());
        mockMvc.perform(get("/api/v1/app/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + otherAccess)
                        .header("X-Tenant-Id", other.tenantId())
                        .header("X-Workspace-Id", other.workspaceId()))
                .andExpect(status().isNotFound());

        // Webhook ingest resolves the phone-number id and stays idempotent without query tenant ids.
        mockMvc.perform(post("/api/v1/app/connected-accounts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"whatsapp","displayName":"WA Mock","accessToken":"secret-token","ownershipMode":"CUSTOMER","externalAccountRef":"100200300400500"}
                                """))
                .andExpect(status().isCreated());
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"waba-p3","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550001111","phone_number_id":"100200300400500"},"messages":[{"from":"15550009999","id":"wamid.p3","type":"text","text":{"body":"hi from wa"}}]}}]}]}
                """;
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(true));
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(false));

        // Connected account + template
        mockMvc.perform(post("/api/v1/app/connected-accounts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"whatsapp","displayName":"WA Mock","accessToken":"secret-token","ownershipMode":"CUSTOMER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("whatsapp"));

        mockMvc.perform(post("/api/v1/app/templates")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"WHATSAPP","name":"hello_world","language":"en","status":"APPROVED","bodyPreview":"Hello"}
                                """))
                .andExpect(status().isCreated());

        // WhatsApp conversation + consent-gated send without OPTED_IN should fail
        MvcResult waConv = mockMvc.perform(post("/api/v1/app/conversations")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "WHATSAPP",
                                "contactId", contactId
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String waConversationId = objectMapper.readTree(waConv.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/app/conversations/" + waConversationId + "/messages")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bodyText":"Promo","purpose":"MARKETING","destinationAddress":"+15550001111"}
                                """))
                .andExpect(status().isBadRequest());

        // Opt in then send
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/app/contacts/" + contactId + "/consents")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType":"WHATSAPP","purpose":"MARKETING","status":"OPTED_IN","source":"MANUAL","evidenceNote":"test"}
                                """))
                .andExpect(status().isOk());

        MvcResult queued = mockMvc.perform(post("/api/v1/app/conversations/" + waConversationId + "/messages")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bodyText":"Promo ok","purpose":"MARKETING","destinationAddress":"+15550001111","idempotencyKey":"wa-1","templateName":"hello_world","attachments":[{"filename":"a.png","mimeType":"image/png","sizeBytes":12,"providerMediaId":"media-1"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();
        String messageId = objectMapper.readTree(queued.getResponse().getContentAsString()).get("id").asText();
        messageSendWorker.process(
                UUID.fromString(tenant.tenantId()),
                UUID.fromString(tenant.workspaceId()),
                "{\"messageId\":\"" + messageId + "\"}"
        );
        mockMvc.perform(get("/api/v1/app/conversations/" + waConversationId + "/messages")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + messageId + "')].status").value("SENT"));

        // Campaign with no consent on second contact skips
        mockMvc.perform(post("/api/v1/app/contacts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"No Consent","channels":[{"channelType":"PHONE","value":"+15550002222","primary":true}]}
                                """))
                .andExpect(status().isCreated());

        MvcResult campaignCreate = mockMvc.perform(post("/api/v1/app/campaigns")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Blast","channel":"WHATSAPP","purpose":"MARKETING","bodyText":"Hi all"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String campaignId = objectMapper.readTree(campaignCreate.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/app/campaigns/" + campaignId + "/start")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        campaignService.processEnqueue(
                UUID.fromString(tenant.tenantId()),
                UUID.fromString(tenant.workspaceId()),
                UUID.fromString(campaignId));

        Integer skipped = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id = ? AND status = 'SKIPPED'",
                Integer.class, UUID.fromString(campaignId)
        );
        assertThat(skipped).isGreaterThanOrEqualTo(1);

        // Assignment + note
        mockMvc.perform(post("/api/v1/app/conversations/" + conversationId + "/assignment")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "assigneeUserId",
                                jdbcTemplate.queryForObject(
                                        "SELECT id FROM customer_users WHERE email_normalized = 'owner-p3@example.com'",
                                        UUID.class
                                )
                        ))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/app/conversations/" + conversationId + "/notes")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bodyText\":\"Internal only\"}"))
                .andExpect(status().isCreated());

        // Mailbox connect + sync + delivery webhook status
        MvcResult gmail = mockMvc.perform(post("/api/v1/app/connected-accounts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"gmail","displayName":"Gmail Mock","accessToken":"gmail-token"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String gmailId = objectMapper.readTree(gmail.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/app/connected-accounts/" + gmailId + "/sync")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId()))
                .andExpect(status().isAccepted());
        Integer mailboxApplied = jdbcTemplate.query(
                """
                SELECT payload_json::text AS payload FROM outbox_events
                WHERE event_type = 'MAILBOX_SYNC' AND tenant_id = ?
                ORDER BY created_at DESC LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return 0;
                    }
                    return mailboxSyncService.processSync(
                            UUID.fromString(tenant.tenantId()),
                            UUID.fromString(tenant.workspaceId()),
                            rs.getString("payload")
                    );
                },
                UUID.fromString(tenant.tenantId())
        );
        assertThat(mailboxApplied).isGreaterThanOrEqualTo(1);

        mockMvc.perform(post("/api/v1/app/connected-accounts")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", tenant.tenantId())
                        .header("X-Workspace-Id", tenant.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"sendgrid","displayName":"SG","accessToken":"sg-token","externalAccountRef":"sg-p3-account"}
                                """))
                .andExpect(status().isCreated());

        String providerMsgId = "email-mock-" + messageId;
        jdbcTemplate.update(
                "UPDATE messages SET provider_message_id = ? WHERE id = ?",
                providerMsgId, UUID.fromString(messageId)
        );
        mockMvc.perform(post("/api/v1/webhooks/sendgrid")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountRef\":\"sg-p3-account\",\"providerMessageId\":\"" + providerMsgId + "\",\"event\":\"delivered\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(true));
        String deliveryStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE id = ?",
                String.class, UUID.fromString(messageId)
        );
        assertThat(deliveryStatus).isEqualTo("DELIVERED");
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
