package com.rsreach.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderErrorClassifierTest {

    @Test
    void classifiesRateLimitsAsRetryable() {
        assertThat(ProviderErrorClassifier.isRetryable("429", null)).isTrue();
        assertThat(ProviderErrorClassifier.isRetryable("throttled", "ok")).isTrue();
        assertThat(ProviderErrorClassifier.isRetryable("131047", "policy")).isFalse();
    }
}
