package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "rsreach.bootstrap.staff-email=owner@webhook-mock-off.local",
        "rsreach.bootstrap.staff-password=OwnerPass123!",
        "rsreach.webhooks.allow-mock-signatures=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.WebhookMockSignatureDisabledTest#dockerAvailable")
class WebhookMockSignatureDisabledTest {

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
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void mockSignatureIsRejectedWhenDisabled() throws Exception {
        String phone = "pn" + UUID.randomUUID().toString().replace("-", "");
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"waba","changes":[{"field":"messages","value":{"metadata":{"phone_number_id":"%s"}}}]}]}
                """.formatted(phone);
        mockMvc.perform(post("/api/v1/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("invalid_signature"))
                .andExpect(jsonPath("$.allowMockSignatures").value(false));
        mockMvc.perform(post("/api/v1/webhooks/sendgrid")
                        .header("X-Hub-Signature-256", "mock-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"event\":\"delivered\",\"account_ref\":\"sg-off\"}]".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("invalid_signature"));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_webhook_events WHERE connected_account_ref IN (?, ?)",
                Long.class,
                phone,
                "sg-off"
        );
        assertThat(count).isZero();
    }
}
