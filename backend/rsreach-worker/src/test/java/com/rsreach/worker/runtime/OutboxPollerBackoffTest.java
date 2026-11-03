package com.rsreach.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxPollerBackoffTest {

    @Test
    void backoffIncreasesThenCaps() {
        assertThat(OutboxPoller.backoffSeconds(1)).isEqualTo(30L);
        assertThat(OutboxPoller.backoffSeconds(2)).isEqualTo(120L);
        assertThat(OutboxPoller.backoffSeconds(3)).isEqualTo(600L);
        assertThat(OutboxPoller.backoffSeconds(4)).isEqualTo(3600L);
        assertThat(OutboxPoller.backoffSeconds(99)).isEqualTo(3600L);
    }
}
