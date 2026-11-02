package com.rsreach.security.jwt;

import com.rsreach.common.RsReachConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    public String issueAccessToken(String realm, UUID subjectId, boolean mfaVerified) {
        JwtProperties.RealmProperties realmProps = realmProps(realm);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(realmProps.getAccessTokenTtlSeconds());
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subjectId.toString())
                .issuer(realmProps.getIssuer())
                .audience().add(realmProps.getAudience()).and()
                .claim("realm", realm)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey(realmProps.getSecret()));
        if (RsReachConstants.REALM_STAFF.equals(realm)) {
            builder.claim("mfa_verified", mfaVerified);
            if (mfaVerified) {
                builder.claim("mfa_verified_at", now.toString());
            }
        }
        return builder.compact();
    }

    public ParsedJwt parse(String token, String expectedRealm) {
        JwtProperties.RealmProperties realmProps = realmProps(expectedRealm);
        Claims claims = Jwts.parser()
                .verifyWith(signingKey(realmProps.getSecret()))
                .requireIssuer(realmProps.getIssuer())
                .requireAudience(realmProps.getAudience())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String realm = claims.get("realm", String.class);
        if (!expectedRealm.equals(realm)) {
            throw new IllegalArgumentException("JWT realm mismatch");
        }
        boolean mfaVerified = Boolean.TRUE.equals(claims.get("mfa_verified", Boolean.class));
        return new ParsedJwt(
                UUID.fromString(claims.getSubject()),
                realm,
                claims.getId(),
                claims.getExpiration().toInstant(),
                mfaVerified
        );
    }

    public long refreshTtlSeconds(String realm) {
        return realmProps(realm).getRefreshTokenTtlSeconds();
    }

    private JwtProperties.RealmProperties realmProps(String realm) {
        if (RsReachConstants.REALM_CUSTOMER.equals(realm)) {
            return properties.getCustomer();
        }
        if (RsReachConstants.REALM_STAFF.equals(realm)) {
            return properties.getStaff();
        }
        throw new IllegalArgumentException("Unknown realm: " + realm);
    }

    private SecretKey signingKey(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be configured and at least 32 characters");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public record ParsedJwt(UUID subjectId, String realm, String jti, Instant expiresAt, boolean mfaVerified) {
    }
}
