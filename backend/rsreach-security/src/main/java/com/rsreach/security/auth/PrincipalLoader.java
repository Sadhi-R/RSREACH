package com.rsreach.security.auth;

import java.util.Optional;
import java.util.UUID;

public interface PrincipalLoader {

    Optional<RsReachPrincipal> loadCustomer(UUID userId, boolean mfaVerified);

    Optional<RsReachPrincipal> loadStaff(UUID userId, boolean mfaVerified);
}
