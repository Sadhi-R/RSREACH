package com.rsreach.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetaWebhookSignatureTest {

    @Test
    void verifiesHmacSha256Header() {
        String secret = "app-secret-material";
        String body = "{\"text\":\"hi\"}";
        String hex = MetaWebhookSignature.hmacHex(secret, body);
        assertThat(MetaWebhookSignature.verifySha256(secret, "sha256=" + hex, body)).isTrue();
        assertThat(MetaWebhookSignature.verifySha256(secret, "sha256=deadbeef", body)).isFalse();
    }
}
