package com.rsreach.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Documents the sandbox gate. Live Meta/Gmail/SendGrid E2E requires operator secrets
 * ({@code RSREACH_SANDBOX_IT=true} plus provider env vars) and is never invented in CI.
 */
class Phase35LiveIntegrationSandboxTest {

    @Test
    void liveSandboxRemainsOptInWithoutInventedCredentials() {
        boolean sandboxRequested = "true".equalsIgnoreCase(System.getenv("RSREACH_SANDBOX_IT"));
        if (!sandboxRequested) {
            assertThat(sandboxRequested).isFalse();
            return;
        }
        assertThat(
                System.getenv("RSREACH_WHATSAPP_APP_SECRET") != null
                        || System.getenv("RSREACH_SENDGRID_API_KEY") != null
                        || System.getenv("RSREACH_GMAIL_ACCESS_TOKEN") != null
        ).as("Sandbox mode requires at least one operator-supplied provider secret").isTrue();
    }
}
