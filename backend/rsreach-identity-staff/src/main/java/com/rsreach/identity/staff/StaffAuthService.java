package com.rsreach.identity.staff;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.secrets.EncryptedSecret;
import com.rsreach.persistence.secrets.IntegrationSecretRepository;
import com.rsreach.persistence.secrets.SecretEncryptionService;
import com.rsreach.security.auth.TokenHasher;
import com.rsreach.security.jwt.JwtService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffAuthService {

    private final StaffUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final TotpService totpService;
    private final SecretEncryptionService secretEncryptionService;
    private final IntegrationSecretRepository integrationSecretRepository;
    private final String secretsKeyAlias;
    private final int secretsKeyVersion;

    public StaffAuthService(
            StaffUserRepository repository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuditService auditService,
            TotpService totpService,
            SecretEncryptionService secretEncryptionService,
            IntegrationSecretRepository integrationSecretRepository,
            @Value("${rsreach.secrets.active-key-alias:rsreach-master}") String secretsKeyAlias,
            @Value("${rsreach.secrets.active-key-version:1}") int secretsKeyVersion
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.totpService = totpService;
        this.secretEncryptionService = secretEncryptionService;
        this.integrationSecretRepository = integrationSecretRepository;
        this.secretsKeyAlias = secretsKeyAlias;
        this.secretsKeyVersion = secretsKeyVersion;
    }

    @Transactional
    public Object login(String email, String password, String userAgent, String ip) {
        StaffUserRepository.StaffUserRecord user = repository.findByEmailNormalized(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Invalid credentials"));
        if (!"ACTIVE".equals(user.status()) || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid credentials");
        }
        auditService.record(RsReachConstants.REALM_STAFF, user.id(), null, null,
                "staff.login_attempt", "staff_user", user.id().toString(), "{}");

        if (user.mfaRequired()) {
            if (!repository.hasEnabledMfa(user.id())) {
                String secret = totpService.generateSecret();
                ensureKeyVersion();
                EncryptedSecret encrypted = secretEncryptionService.encrypt(
                        secretsKeyAlias, secretsKeyVersion, secret.getBytes(StandardCharsets.UTF_8));
                String secretName = "staff.mfa.totp." + user.id();
                integrationSecretRepository.saveSecret(null, null, secretName, encrypted);
                repository.upsertTotpFactor(user.id(), secretName, true);
                String challenge = TokenHasher.newOpaqueToken();
                repository.storeMfaChallenge(user.id(), TokenHasher.sha256(challenge), Instant.now().plusSeconds(300));
                return new MfaRequiredResponse("MFA_REQUIRED", challenge, secret, true);
            }
            String challenge = TokenHasher.newOpaqueToken();
            repository.storeMfaChallenge(user.id(), TokenHasher.sha256(challenge), Instant.now().plusSeconds(300));
            return new MfaRequiredResponse("MFA_REQUIRED", challenge, null, false);
        }
        return issueTokens(user.id(), true, userAgent, ip);
    }

    @Transactional
    public AuthTokens verifyMfa(String challengeToken, String code, String userAgent, String ip) {
        var challenge = repository.findMfaChallenge(TokenHasher.sha256(challengeToken))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Invalid MFA challenge"));
        if (challenge.consumedAt() != null || challenge.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "MFA challenge expired");
        }
        String secretRef = repository.findTotpSecretRef(challenge.staffUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "MFA not configured"));
        EncryptedSecret encrypted = integrationSecretRepository.findSecret(null, null, secretRef)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "MFA secret missing"));
        String secret = new String(secretEncryptionService.decrypt(encrypted), StandardCharsets.UTF_8);
        if (!totpService.verify(secret, code)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid MFA code");
        }
        repository.consumeMfaChallenge(challenge.id());
        auditService.record(RsReachConstants.REALM_STAFF, challenge.staffUserId(), null, null,
                "staff.mfa_verified", "staff_user", challenge.staffUserId().toString(), "{}");
        return issueTokens(challenge.staffUserId(), true, userAgent, ip);
    }

    @Transactional
    public AuthTokens refresh(String refreshToken, String userAgent, String ip) {
        String hash = TokenHasher.sha256(refreshToken);
        var existing = repository.findRefreshByHash(hash)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Invalid refresh token"));
        if (existing.revokedAt() != null || existing.expiresAt().isBefore(Instant.now())) {
            repository.revokeFamily(existing.familyId());
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Refresh token revoked or expired");
        }
        AuthTokens tokens = issueTokens(existing.userId(), existing.familyId(), true, userAgent, ip);
        repository.revokeRefresh(existing.id());
        return tokens;
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            repository.revokeByHash(TokenHasher.sha256(refreshToken));
        }
    }

    private void ensureKeyVersion() {
        integrationSecretRepository.upsertKeyVersion(secretsKeyAlias, secretsKeyVersion, "ACTIVE");
    }

    private AuthTokens issueTokens(UUID userId, boolean mfaVerified, String ua, String ip) {
        return issueTokens(userId, UUID.randomUUID(), mfaVerified, ua, ip);
    }

    private AuthTokens issueTokens(UUID userId, UUID familyId, boolean mfaVerified, String ua, String ip) {
        String access = jwtService.issueAccessToken(RsReachConstants.REALM_STAFF, userId, mfaVerified);
        String refresh = TokenHasher.newOpaqueToken();
        Instant exp = Instant.now().plusSeconds(jwtService.refreshTtlSeconds(RsReachConstants.REALM_STAFF));
        repository.storeRefreshToken(userId, TokenHasher.sha256(refresh), familyId, exp, ua, ip);
        return new AuthTokens(access, refresh, "Bearer", jwtService.refreshTtlSeconds(RsReachConstants.REALM_STAFF));
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthTokens(String accessToken, String refreshToken, String tokenType, long refreshExpiresInSeconds) {
    }

    public record MfaRequiredResponse(String status, String mfaToken, String enrollmentSecret, boolean enrollment) {
    }
}
