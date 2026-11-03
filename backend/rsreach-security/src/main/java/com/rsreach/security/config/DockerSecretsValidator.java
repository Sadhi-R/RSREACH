package com.rsreach.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast under the docker profile when JWT or envelope master key material is missing.
 */
@Component
@Order(0)
public class DockerSecretsValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerSecretsValidator.class);

    private final Environment environment;

    public DockerSecretsValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!environment.matchesProfiles("docker")) {
            return;
        }
        String master = firstNonBlank(
                environment.getProperty("rsreach.secrets.master-key"),
                environment.getProperty("RSREACH_SECRETS_MASTER_KEY")
        );
        require("RSREACH_SECRETS_MASTER_KEY", master);
        String role = environment.getProperty("rsreach.app.role", "api");
        if ("api".equalsIgnoreCase(role)) {
            require("RSREACH_JWT_CUSTOMER_SECRET", environment.getProperty("rsreach.jwt.customer.secret"));
            require("RSREACH_JWT_STAFF_SECRET", environment.getProperty("rsreach.jwt.staff.secret"));
            require(
                    "RSREACH_RAZORPAY_WEBHOOK_SECRET",
                    firstNonBlank(
                            environment.getProperty("rsreach.billing.razorpay.webhook-secret"),
                            environment.getProperty("RSREACH_RAZORPAY_WEBHOOK_SECRET")
                    )
            );
        }
        log.info("docker_secrets_validated role={}", role);
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank() || value.startsWith("replace-with-")) {
            throw new IllegalStateException(
                    "Docker profile requires a non-empty " + name + " (fail-fast; refuse blank/example secrets)");
        }
        if (value.length() < 16) {
            throw new IllegalStateException(name + " is too short for docker profile (min 16 characters)");
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
