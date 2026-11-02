package com.rsreach.admin;

import com.rsreach.identity.staff.StaffUserRepository;
import com.rsreach.persistence.secrets.IntegrationSecretRepository;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

    private final StaffUserRepository staffUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final IntegrationSecretRepository integrationSecretRepository;
    private final String email;
    private final String password;
    private final String keyAlias;
    private final int keyVersion;

    public StaffBootstrap(
            StaffUserRepository staffUserRepository,
            PasswordEncoder passwordEncoder,
            IntegrationSecretRepository integrationSecretRepository,
            @Value("${rsreach.bootstrap.staff-email:}") String email,
            @Value("${rsreach.bootstrap.staff-password:}") String password,
            @Value("${rsreach.secrets.active-key-alias:rsreach-master}") String keyAlias,
            @Value("${rsreach.secrets.active-key-version:1}") int keyVersion
    ) {
        this.staffUserRepository = staffUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.integrationSecretRepository = integrationSecretRepository;
        this.email = email;
        this.password = password;
        this.keyAlias = keyAlias;
        this.keyVersion = keyVersion;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        integrationSecretRepository.upsertKeyVersion(keyAlias, keyVersion, "ACTIVE");
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.info("Staff bootstrap skipped (RSREACH_BOOTSTRAP_STAFF_EMAIL/PASSWORD not set)");
            return;
        }
        if (staffUserRepository.countStaff() > 0) {
            log.info("Staff bootstrap skipped (staff users already exist)");
            return;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        var id = staffUserRepository.insertStaff(
                email.trim(), normalized, "Platform Owner", passwordEncoder.encode(password), true);
        staffUserRepository.bindRole(id, "PLATFORM_OWNER");
        log.info("Bootstrapped Platform Owner staff user {}", normalized);
    }
}
