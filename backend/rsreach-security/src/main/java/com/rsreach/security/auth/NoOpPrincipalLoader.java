package com.rsreach.security.auth;

import java.util.Optional;
import java.util.UUID;

/**
 * Used by non-API processes (worker/scheduler). Registered as a {@code @Bean}
 * from {@link com.rsreach.security.config.SecurityConfig} when no other loader exists.
 */
public class NoOpPrincipalLoader implements PrincipalLoader {

    @Override
    public Optional<RsReachPrincipal> loadCustomer(UUID userId, boolean mfaVerified) {
        return Optional.empty();
    }

    @Override
    public Optional<RsReachPrincipal> loadStaff(UUID userId, boolean mfaVerified) {
        return Optional.empty();
    }
}
