package com.rsreach.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsreach.jwt")
public class JwtProperties {

    private RealmProperties customer = new RealmProperties();
    private RealmProperties staff = new RealmProperties();

    public RealmProperties getCustomer() {
        return customer;
    }

    public void setCustomer(RealmProperties customer) {
        this.customer = customer;
    }

    public RealmProperties getStaff() {
        return staff;
    }

    public void setStaff(RealmProperties staff) {
        this.staff = staff;
    }

    public static class RealmProperties {
        private String secret = "";
        private String issuer = "rsreach";
        private String audience = "rsreach-api";
        private long accessTokenTtlSeconds = 900;
        private long refreshTokenTtlSeconds = 2592000;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public long getAccessTokenTtlSeconds() {
            return accessTokenTtlSeconds;
        }

        public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
            this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        }

        public long getRefreshTokenTtlSeconds() {
            return refreshTokenTtlSeconds;
        }

        public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
            this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        }
    }
}
