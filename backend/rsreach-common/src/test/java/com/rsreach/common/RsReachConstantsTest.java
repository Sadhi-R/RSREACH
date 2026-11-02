package com.rsreach.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RsReachConstantsTest {

    @Test
    void apiPrefixIsVersioned() {
        assertEquals("/api/v1", RsReachConstants.API_V1);
    }
}
