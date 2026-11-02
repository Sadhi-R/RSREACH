package com.rsreach.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lightweight sanity check that does not require Docker/PostgreSQL.
 */
class RsReachApiApplicationTest {

    @Test
    void mainClassNameIsStable() {
        assertEquals("com.rsreach.api.RsReachApiApplication", RsReachApiApplication.class.getName());
    }
}
