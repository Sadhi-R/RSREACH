package com.rsreach.api.security;

import com.rsreach.identity.customer.CustomerUserRepository;
import com.rsreach.identity.staff.StaffUserRepository;
import com.rsreach.security.auth.PrincipalLoader;
import com.rsreach.security.auth.RsReachPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CompositePrincipalLoader implements PrincipalLoader {

    private final CustomerUserRepository customerUserRepository;
    private final StaffUserRepository staffUserRepository;

    public CompositePrincipalLoader(
            CustomerUserRepository customerUserRepository,
            StaffUserRepository staffUserRepository
    ) {
        this.customerUserRepository = customerUserRepository;
        this.staffUserRepository = staffUserRepository;
    }

    @Override
    public Optional<RsReachPrincipal> loadCustomer(UUID userId, boolean mfaVerified) {
        // Customer permissions are resolved per active tenant/workspace at authorization time.
        // Never load a flat union across memberships into the principal.
        return customerUserRepository.findById(userId)
                .filter(u -> "ACTIVE".equals(u.status()))
                .map(u -> new RsReachPrincipal(
                        u.id(),
                        "customer",
                        u.email(),
                        java.util.Set.of(),
                        false
                ));
    }

    @Override
    public Optional<RsReachPrincipal> loadStaff(UUID userId, boolean mfaVerified) {
        return staffUserRepository.findById(userId)
                .filter(u -> "ACTIVE".equals(u.status()))
                .map(u -> new RsReachPrincipal(
                        u.id(),
                        "staff",
                        u.email(),
                        staffUserRepository.findPermissions(u.id()),
                        mfaVerified
                ));
    }
}
