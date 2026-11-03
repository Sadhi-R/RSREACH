package com.rsreach.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.profiles.active=docker",
        "rsreach.security.permit-unauthenticated-api=false",
        "rsreach.secrets.master-key=test-master-key-material-p0",
        "rsreach.jwt.customer.secret=test-customer-jwt-secret-p0",
        "rsreach.jwt.staff.secret=test-staff-jwt-secret-p0!!!!",
        "rsreach.billing.razorpay.webhook-secret=test-razorpay-webhook-p0"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.rsreach.api.Phase0SecurityExposureTest#dockerAvailable")
class Phase0SecurityExposureTest {

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
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthRemainsPublicAndSensitiveRoutesAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/system/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/system/info")).andExpect(status().isForbidden());
    }
}
