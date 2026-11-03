package com.rsreach.contacts;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Canonical UUID pair ordering using PostgreSQL LEAST/GREATEST.
 * Do not use {@link UUID#compareTo(UUID)} — it does not match PostgreSQL UUID order.
 */
public final class PostgresUuidOrdering {

    private PostgresUuidOrdering() {
    }

    public record Pair(UUID low, UUID high) {
    }

    public static Pair canonical(JdbcTemplate jdbcTemplate, UUID a, UUID b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("UUIDs required");
        }
        if (a.equals(b)) {
            throw new IllegalArgumentException("Pair contacts must be distinct");
        }
        return jdbcTemplate.query(
                "SELECT LEAST(?::uuid, ?::uuid) AS low_id, GREATEST(?::uuid, ?::uuid) AS high_id",
                rs -> {
                    rs.next();
                    return new Pair((UUID) rs.getObject("low_id"), (UUID) rs.getObject("high_id"));
                },
                a, b, a, b
        );
    }
}
