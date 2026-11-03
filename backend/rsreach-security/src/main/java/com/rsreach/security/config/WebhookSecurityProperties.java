package com.rsreach.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsreach.webhooks")
public class WebhookSecurityProperties {

    /**
     * When true, accepts local mock signatures such as {@code mock-valid}.
     * Default true for local/CI; docker profile sets false.
     */
    private boolean allowMockSignatures = true;

    public boolean isAllowMockSignatures() {
        return allowMockSignatures;
    }

    public void setAllowMockSignatures(boolean allowMockSignatures) {
        this.allowMockSignatures = allowMockSignatures;
    }
}
