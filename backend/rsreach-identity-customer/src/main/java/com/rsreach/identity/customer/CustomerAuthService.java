package com.rsreach.identity.customer;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import com.rsreach.security.auth.TokenHasher;
import com.rsreach.security.config.DevTokenExposureGate;
import com.rsreach.security.jwt.JwtService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerAuthService {

    private final CustomerUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final OutboxRepository outboxRepository;
    private final DevTokenExposureGate devTokenExposureGate;

    public CustomerAuthService(
            CustomerUserRepository repository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuditService auditService,
            OutboxRepository outboxRepository,
            DevTokenExposureGate devTokenExposureGate
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.outboxRepository = outboxRepository;
        this.devTokenExposureGate = devTokenExposureGate;
    }

    @Transactional
    public AuthTokens register(String email, String displayName, String password) {
        String normalized = normalizeEmail(email);
        if (repository.findByEmailNormalized(normalized).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "Email already registered");
        }
        if (password == null || password.length() < 8) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must be at least 8 characters");
        }
        UUID userId = repository.insertUser(email.trim(), normalized, displayName.trim(), passwordEncoder.encode(password));
        auditService.record(RsReachConstants.REALM_CUSTOMER, userId, null, null,
                "customer.register", "customer_user", userId.toString(), "{}");
        return issueTokens(userId, null, null);
    }

    @Transactional
    public AuthTokens login(String email, String password, String userAgent, String ip) {
        CustomerUserRepository.CustomerUserRecord user = repository.findByEmailNormalized(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Invalid credentials"));
        if (!"ACTIVE".equals(user.status()) || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid credentials");
        }
        auditService.record(RsReachConstants.REALM_CUSTOMER, user.id(), null, null,
                "customer.login", "customer_user", user.id().toString(), "{}");
        return issueTokens(user.id(), userAgent, ip);
    }

    @Transactional
    public AuthTokens refresh(String refreshToken, String userAgent, String ip) {
        String hash = TokenHasher.sha256(refreshToken);
        CustomerUserRepository.RefreshTokenRecord existing = repository.findRefreshByHash(hash)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Invalid refresh token"));
        if (existing.revokedAt() != null || existing.expiresAt().isBefore(Instant.now())) {
            repository.revokeFamily(existing.familyId());
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Refresh token revoked or expired");
        }
        AuthTokens tokens = issueTokens(existing.userId(), existing.familyId(), userAgent, ip);
        repository.revokeRefresh(existing.id(), null);
        return tokens;
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        repository.revokeByHash(TokenHasher.sha256(refreshToken));
    }

    @Transactional
    public Map<String, String> requestPasswordReset(String email) {
        var userOpt = repository.findByEmailNormalized(normalizeEmail(email));
        // Always return the same accepted shape to avoid account enumeration.
        Map<String, String> response = new HashMap<>();
        response.put("status", "ACCEPTED");
        if (userOpt.isPresent()) {
            String raw = TokenHasher.newOpaqueToken();
            repository.insertPasswordReset(userOpt.get().id(), TokenHasher.sha256(raw), Instant.now().plusSeconds(3600));
            outboxRepository.append(OutboxAppendCommand.of(
                    "customer_user",
                    userOpt.get().id(),
                    "CUSTOMER_PASSWORD_RESET_REQUESTED",
                    "{\"email\":\"" + userOpt.get().email() + "\"}",
                    null,
                    null,
                    null,
                    "pwd-reset-" + userOpt.get().id() + "-" + Instant.now().getEpochSecond()
            ));
            if (devTokenExposureGate.isEnabled()) {
                response.put("devResetToken", raw);
            }
        }
        return response;
    }

    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must be at least 8 characters");
        }
        var reset = repository.findPasswordReset(TokenHasher.sha256(token))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Invalid reset token"));
        if (reset.usedAt() != null || reset.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Reset token expired or used");
        }
        repository.updatePassword(reset.userId(), passwordEncoder.encode(newPassword));
        repository.markPasswordResetUsed(reset.id());
        auditService.record(RsReachConstants.REALM_CUSTOMER, reset.userId(), null, null,
                "customer.password_reset", "customer_user", reset.userId().toString(), "{}");
    }

    private AuthTokens issueTokens(UUID userId, String userAgent, String ip) {
        return issueTokens(userId, UUID.randomUUID(), userAgent, ip);
    }

    private AuthTokens issueTokens(UUID userId, UUID familyId, String userAgent, String ip) {
        String access = jwtService.issueAccessToken(RsReachConstants.REALM_CUSTOMER, userId, false);
        String refresh = TokenHasher.newOpaqueToken();
        Instant exp = Instant.now().plusSeconds(jwtService.refreshTtlSeconds(RsReachConstants.REALM_CUSTOMER));
        repository.storeRefreshToken(userId, TokenHasher.sha256(refresh), familyId, exp, userAgent, ip);
        return new AuthTokens(access, refresh, "Bearer", jwtService.refreshTtlSeconds(RsReachConstants.REALM_CUSTOMER));
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthTokens(String accessToken, String refreshToken, String tokenType, long refreshExpiresInSeconds) {
    }
}
