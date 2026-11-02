package com.rsreach.identity.customer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public CustomerUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CustomerUserRecord> findByEmailNormalized(String emailNormalized) {
        List<CustomerUserRecord> rows = jdbcTemplate.query(
                """
                SELECT u.id, u.email, u.email_normalized, u.display_name, u.status, c.password_hash
                FROM customer_users u
                JOIN customer_credentials c ON c.user_id = u.id
                WHERE u.email_normalized = ?
                """,
                (rs, i) -> new CustomerUserRecord(
                        (UUID) rs.getObject("id"),
                        rs.getString("email"),
                        rs.getString("email_normalized"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("password_hash")
                ),
                emailNormalized
        );
        return rows.stream().findFirst();
    }

    public Optional<CustomerUserRecord> findById(UUID id) {
        List<CustomerUserRecord> rows = jdbcTemplate.query(
                """
                SELECT u.id, u.email, u.email_normalized, u.display_name, u.status, c.password_hash
                FROM customer_users u
                JOIN customer_credentials c ON c.user_id = u.id
                WHERE u.id = ?
                """,
                (rs, i) -> new CustomerUserRecord(
                        (UUID) rs.getObject("id"),
                        rs.getString("email"),
                        rs.getString("email_normalized"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("password_hash")
                ),
                id
        );
        return rows.stream().findFirst();
    }

    public UUID insertUser(String email, String emailNormalized, String displayName, String passwordHash) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO customer_users (id, email, email_normalized, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                id, email, emailNormalized, displayName
        );
        jdbcTemplate.update(
                """
                INSERT INTO customer_credentials (user_id, password_hash, password_algo, updated_at)
                VALUES (?, ?, 'BCRYPT', NOW())
                """,
                id, passwordHash
        );
        return id;
    }

    public void updatePassword(UUID userId, String passwordHash) {
        jdbcTemplate.update(
                "UPDATE customer_credentials SET password_hash = ?, updated_at = NOW() WHERE user_id = ?",
                passwordHash, userId
        );
    }

    public void storeRefreshToken(UUID userId, String tokenHash, UUID familyId, Instant expiresAt, String ua, String ip) {
        jdbcTemplate.update(
                """
                INSERT INTO customer_refresh_tokens
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
                FROM customer_refresh_tokens WHERE token_hash = ?
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

    public void revokeRefresh(UUID id, UUID replacedBy) {
        jdbcTemplate.update(
                "UPDATE customer_refresh_tokens SET revoked_at = NOW(), replaced_by = ? WHERE id = ?",
                replacedBy, id
        );
    }

    public void revokeFamily(UUID familyId) {
        jdbcTemplate.update(
                "UPDATE customer_refresh_tokens SET revoked_at = NOW() WHERE family_id = ? AND revoked_at IS NULL",
                familyId
        );
    }

    public void revokeByHash(String tokenHash) {
        jdbcTemplate.update(
                "UPDATE customer_refresh_tokens SET revoked_at = NOW() WHERE token_hash = ? AND revoked_at IS NULL",
                tokenHash
        );
    }

    public void insertPasswordReset(UUID userId, String tokenHash, Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, NOW())
                """,
                UUID.randomUUID(), userId, tokenHash, Timestamp.from(expiresAt)
        );
    }

    public Optional<PasswordResetRecord> findPasswordReset(String tokenHash) {
        List<PasswordResetRecord> rows = jdbcTemplate.query(
                """
                SELECT id, user_id, expires_at, used_at FROM password_reset_tokens WHERE token_hash = ?
                """,
                (rs, i) -> new PasswordResetRecord(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("user_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant()
                ),
                tokenHash
        );
        return rows.stream().findFirst();
    }

    public void markPasswordResetUsed(UUID id) {
        jdbcTemplate.update("UPDATE password_reset_tokens SET used_at = NOW() WHERE id = ?", id);
    }

    /**
     * @deprecated Customer permissions must be resolved via
     * {@code CustomerAuthorizationService} against the active tenant/workspace.
     * This flat union is retained only for emergency diagnostics and must not drive authorization.
     */
    @Deprecated
    public Set<String> findPermissionsForUser(UUID userId) {
        return Set.of();
    }

    public record CustomerUserRecord(
            UUID id, String email, String emailNormalized, String displayName, String status, String passwordHash
    ) {
    }

    public record RefreshTokenRecord(
            UUID id, UUID userId, String tokenHash, UUID familyId, Instant expiresAt, Instant revokedAt
    ) {
    }

    public record PasswordResetRecord(UUID id, UUID userId, Instant expiresAt, Instant usedAt) {
    }
}
