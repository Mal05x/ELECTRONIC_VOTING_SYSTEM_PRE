package com.evoting.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

/**
 * JWT provider with JTI (JWT ID) support for token revocation.
 *
 * Every token now carries a unique jti claim. On logout, the jti is stored
 * in Redis with TTL = remaining token lifetime. JwtAuthFilter checks the
 * blacklist before granting access.
 */
@Component
public class JwtTokenProvider {

    @Value("${security.jwt.secret}")
    private String secret;

    private static final long EXPIRY_MS = 3_600_000L; // 1 hour

    public String generateToken(String username, String role) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + EXPIRY_MS);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — unique per token
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key(), Jwts.SIG.HS512)
                .compact();
    }

    public boolean isValid(String token) {
        try {
            Claims c = claims(token);
            return !c.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) { return false; }
    }

    public String getUsername(String token) { return claims(token).getSubject(); }
    public String getRole(String token)     { return claims(token).get("role", String.class); }

    /** Returns the JTI (unique token ID) — used as the Redis revocation key. */
    public String getJti(String token)      { return claims(token).getId(); }

    /** Returns how many seconds until this token expires (used to set Redis TTL). */
    public long getRemainingSeconds(String token) {
        Date expiry = claims(token).getExpiration();
        long diff   = expiry.getTime() - System.currentTimeMillis();
        return Math.max(0L, diff / 1000L);
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }

    private SecretKey key() { return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)); }
}
