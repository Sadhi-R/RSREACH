package com.rsreach.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * UTC clock helper. Domain code should prefer injected Clock in later phases.
 */
public final class UtcClock {

    private static final Clock UTC = Clock.systemUTC();

    private UtcClock() {
    }

    public static Instant now() {
        return Instant.now(UTC);
    }

    public static Clock clock() {
        return UTC;
    }

    public static ZoneOffset zone() {
        return ZoneOffset.UTC;
    }
}
