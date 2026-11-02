package com.rsreach.identity.staff;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StaffUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public StaffUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countStaff() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM staff_users", Long.class);
        return count == null ? 0L : count;
    }

    public Optional<StaffUserRecord> findByEmailNormalized(String emailNormalized) {
        List<StaffUserRecord> rows = jdbcTemplate.query(
                """
                SELECT u.id, u.email, u.email_normalized, u.display_name, u.status, u.mfa_required, c.password_hash
                FROM staff_users u
                JOIN staff_credentials c ON c.user_id = u.id
                WHERE u.email_normalized = ?
                """,
                (rs, i) -> new StaffUserRecord(
                        (UUID) rs.getObject("id"),
                        rs.getString("email"),
                        rs.getString("email_normalized"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getBoolean("mfa_required"),
                        rs.getString("password_hash")
                ),
                emailNormalized
        );
        return rows.stream().findFirst();
    }

    public Optional<StaffUserRecord> findById(UUID id) {
        List<StaffUserRecord> rows = jdbcTemplate.query(
                """
                SELECT u.id, u.email, u.email_normalized, u.display_name, u.status, u.mfa_required, c.password_hash
                FROM staff_users u
                JOIN staff_credentials c ON c.user_id = u.id
                WHERE u.id = ?
                """,
                (rs, i) -> new StaffUserRecord(
                        (UUID) rs.getObject("id"),
                        rs.getString("email"),
                        rs.getString("email_normalized"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getBoolean("mfa_required"),
                        rs.getString("password_hash")
                ),
                id
        );
        return rows.stream().findFirst();
    }

    public UUID insertStaff(String email, String emailNormalized, String displayName, String passwordHash, boolean mfaRequired) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO staff_users (id, email, email_normalized, display_name, status, mfa_required, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, NOW(), NOW())
                """,
                id, email, emailNormalized, displayName, mfaRequired
        );
        jdbcTemplate.update(
                """
                INSERT INTO staff_credentials (user_id, password_hash, password_algo, updated_at)
                VALUES (?, ?, 'BCRYPT', NOW())
                """,
                id, passwordHash
        );
        return id;
    }

    public void bindRole(UUID staffUserId, String roleCode) {
        jdbcTemplate.update(
                """
                INSERT INTO staff_role_bindings (id, staff_user_id, role_id, created_at)
                SELECT ?, ?, r.id, NOW() FROM roles r WHERE r.code = ?
                ON CONFLICT (staff_user_id, role_id) DO NOTHING
                """,
                UUID.randomUUID(), staffUserId, roleCode
        );
    }

    public Set<String> findPermissions(UUID staffUserId) {
        List<String> perms = jdbcTemplate.query(
                """
                SELECT DISTINCT p.code
                FROM staff_role_bindings b
                JOIN role_permissions rp ON rp.role_id = b.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE b.staff_user_id = ?
                """,
                (rs, i) -> rs.getString("code"),
                staffUserId
        );
        return new HashSet<>(perms);
    }

    public boolean hasEnabledMfa(UUID staffUserId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_mfa_factors WHERE user_id = ? AND enabled = TRUE",
                Integer.class,
                staffUserId
        );
        return count != null && count > 0;
    }

    public void upsertTotpFactor(UUID staffUserId, String secretRef, boolean enabled) {
        jdbcTemplate.update(
                """
                INSERT INTO staff_mfa_factors (id, user_id, factor_type, secret_ref, enabled, verified_at, created_at, updated_at)
                VALUES (?, ?, 'TOTP', ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END, NOW(), NOW())
                ON CONFLICT (user_id, factor_type) DO UPDATE
                SET secret_ref = EXCLUDED.secret_ref,
                    enabled = EXCLUDED.enabled,
                    verified_at = CASE WHEN EXCLUDED.enabled THEN NOW() ELSE staff_mfa_factors.verified_at END,
                    updated_at = NOW()
                """,
                UUID.randomUUID(), staffUserId, secretRef, enabled, enabled
        );
    }

    public Optional<String> findTotpSecretRef(UUID staffUserId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT secret_ref FROM staff_mfa_factors WHERE user_id = ? AND factor_type = 'TOTP' AND enabled = TRUE",
                (rs, i) -> rs.getString("secret_ref"),
                staffUserId
        );
        return rows.stream().findFirst();
    }

    public void storeRefreshToken(UUID userId, String tokenHash, UUID familyId, Instant expiresAt, String ua, String ip) {
        jdbcTemplate.update(
                """
                INSERT INTO staff_refresh_tokens
                    (id, user_id, token_hash, family_id, expires_at, created_at, user_agent, ip_address)
                VALUES (?, ?, ?, ?, ?, NOW(), ?, ?)
                """,
                UUID.randomUUID(), userId, tokenHash, familyId, Timestamp.from(expiresAt), ua, ip
        );
    }

    public Optional<RefreshTokenRecord> findRefreshByHash(String tokenHash) {
        List<RefreshTokenRecord> rows = jdbcTemplate.query(
                """
                SELECT id, user_id, token_hash, family_id, expires_at, revoked_at
                FROM staff_refresh_tokens WHERE token_hash = ?
                """,
                (rs, i) -> new RefreshTokenRecord(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("user_id"),
                        rs.getString("token_hash"),
                        (UUID) rs.getObject("family_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()
                ),
                tokenHash
        );
        return rows.stream().findFirst();
    }

    public void revokeRefresh(UUID id) {
        jdbcTemplate.update("UPDATE staff_refresh_tokens SET revoked_at = NOW() WHERE id = ?", id);
    }

    public void revokeFamily(UUID familyId) {
        jdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET revoked_at = NOW() WHERE family_id = ? AND revoked_at IS NULL",
                familyId
        );
    }

    public void revokeByHash(String tokenHash) {
        jdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET revoked_at = NOW() WHERE token_hash = ? AND revoked_at IS NULL",
                tokenHash
        );
    }

    public void storeMfaChallenge(UUID staffUserId, String challengeHash, Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO staff_mfa_challenges (id, staff_user_id, challenge_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, NOW())
                """,
                UUID.randomUUID(), staffUserId, challengeHash, Timestamp.from(expiresAt)
        );
    }

    public Optional<MfaChallengeRecord> findMfaChallenge(String challengeHash) {
        List<MfaChallengeRecord> rows = jdbcTemplate.query(
                """
                SELECT id, staff_user_id, expires_at, consumed_at
                FROM staff_mfa_challenges WHERE challenge_hash = ?
                """,
                (rs, i) -> new MfaChallengeRecord(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("staff_user_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()
                ),
                challengeHash
        );
        return rows.stream().findFirst();
    }

    public void consumeMfaChallenge(UUID id) {
        jdbcTemplate.update("UPDATE staff_mfa_challenges SET consumed_at = NOW() WHERE id = ?", id);
    }

    public record StaffUserRecord(
            UUID id, String email, String emailNormalized, String displayName, String status,
            boolean mfaRequired, String passwordHash
    ) {
    }

    public record RefreshTokenRecord(
            UUID id, UUID userId, String tokenHash, UUID familyId, Instant expiresAt, Instant revokedAt
    ) {
    }

    public record MfaChallengeRecord(UUID id, UUID staffUserId, Instant expiresAt, Instant consumedAt) {
    }
}
