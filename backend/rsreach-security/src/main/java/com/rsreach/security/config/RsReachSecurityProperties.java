package com.rsreach.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsreach.security")
public class RsReachSecurityProperties {

    /**
     * When true, non-health API routes are open. Must be true only for local development.
     */
    private boolean permitUnauthenticatedApi = false;

    /**
     * When true AND only under the local profile (never docker), password-reset and
     * invitation APIs may include a one-time delivery token in the response for manual testing.
     * Default false. Production and Docker must leave this unset/false.
     */
    private boolean exposeDevTokens = false;

    public boolean isPermitUnauthenticatedApi() {
        return permitUnauthenticatedApi;
    }

    public void setPermitUnauthenticatedApi(boolean permitUnauthenticatedApi) {
        this.permitUnauthenticatedApi = permitUnauthenticatedApi;
    }

    public boolean isExposeDevTokens() {
        return exposeDevTokens;
    }

    public void setExposeDevTokens(boolean exposeDevTokens) {
        this.exposeDevTokens = exposeDevTokens;
    }
}
