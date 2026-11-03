package com.rsreach.security.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class DockerSecretsValidatorTest {

    @Test
    void skipsWhenNotDockerProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        DockerSecretsValidator validator = new DockerSecretsValidator(environment);
        assertThatCode(() -> validator.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void failsFastWhenDockerSecretsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker");
        environment.setProperty("rsreach.app.role", "api");
        environment.setProperty("rsreach.secrets.master-key", "master-key-material-ok");
        environment.setProperty("rsreach.jwt.customer.secret", "");
        environment.setProperty("rsreach.jwt.staff.secret", "staff-secret-value-32chars!!");
        DockerSecretsValidator validator = new DockerSecretsValidator(environment);
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RSREACH_JWT_CUSTOMER_SECRET");
    }

    @Test
    void acceptsConfiguredDockerSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker");
        environment.setProperty("rsreach.app.role", "api");
        environment.setProperty("rsreach.secrets.master-key", "master-key-material-ok");
        environment.setProperty("rsreach.jwt.customer.secret", "customer-secret-value-32b");
        environment.setProperty("rsreach.jwt.staff.secret", "staff-secret-value-32chars!!");
        environment.setProperty("rsreach.billing.razorpay.webhook-secret", "razorpay-webhook-secret-ok");
        DockerSecretsValidator validator = new DockerSecretsValidator(environment);
        assertThatCode(() -> validator.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void failsFastWhenDockerRazorpayWebhookSecretMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker");
        environment.setProperty("rsreach.app.role", "api");
        environment.setProperty("rsreach.secrets.master-key", "master-key-material-ok");
        environment.setProperty("rsreach.jwt.customer.secret", "customer-secret-value-32b");
        environment.setProperty("rsreach.jwt.staff.secret", "staff-secret-value-32chars!!");
        DockerSecretsValidator validator = new DockerSecretsValidator(environment);
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RSREACH_RAZORPAY_WEBHOOK_SECRET");
    }

    @Test
    void workerDockerOnlyRequiresMasterKey() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker");
        environment.setProperty("rsreach.app.role", "worker");
        environment.setProperty("rsreach.secrets.master-key", "master-key-material-ok");
        DockerSecretsValidator validator = new DockerSecretsValidator(environment);
        assertThatCode(() -> validator.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }
}
