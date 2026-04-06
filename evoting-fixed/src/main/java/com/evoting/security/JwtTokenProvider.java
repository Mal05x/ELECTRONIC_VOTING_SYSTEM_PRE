package com.evoting.security;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtTokenProvider {

    @Value("${security.jwt.secret}")
    private String secret;

    private static final long EXPIRY_MS = 3_600_000L; // 1 hour

    public String generateToken(String username, String role) {
        return Jwts.builder()
            .subject(username)
            .claim("role", role)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + EXPIRY_MS))
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

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }

    private SecretKey key() { return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)); }
}
