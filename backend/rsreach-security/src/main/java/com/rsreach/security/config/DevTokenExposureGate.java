package com.rsreach.security.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Gates optional local-only exposure of delivery tokens for manual testing.
 * Disabled by default. Never honored under the docker profile or outside local.
 */
@Component
public class DevTokenExposureGate {

    private final RsReachSecurityProperties securityProperties;
    private final Environment environment;

    public DevTokenExposureGate(RsReachSecurityProperties securityProperties, Environment environment) {
        this.securityProperties = securityProperties;
        this.environment = environment;
    }

    public boolean isEnabled() {
        if (!securityProperties.isExposeDevTokens()) {
            return false;
        }
        if (!environment.matchesProfiles("local")) {
            return false;
        }
        if (environment.matchesProfiles("docker")) {
            return false;
        }
        return true;
    }
}
