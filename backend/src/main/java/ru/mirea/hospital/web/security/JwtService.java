package ru.mirea.hospital.web.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import ru.mirea.hospital.web.config.AppProperties;
import ru.mirea.hospital.web.domain.UserAccount;
import ru.mirea.hospital.web.error.AuthenticationFailedException;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Создаёт и проверяет короткоживущие access JWT. */
@Service
public class JwtService {
    private final AppProperties properties;
    private final SecretKey key;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(properties.jwt().secretBase64());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64", ex);
        }
        if (decoded.length < 32) {
            throw new IllegalStateException("JWT secret must contain at least 32 random bytes");
        }
        this.key = Keys.hmacShaKeyFor(decoded);
    }

    /** Выпускает access-токен и возвращает его срок истечения. */
    public IssuedAccessToken issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().accessMinutes(), ChronoUnit.MINUTES);
        List<String> roles = user.getRoles().stream().map(Enum::name).sorted().toList();
        String token = Jwts.builder()
                .issuer(properties.jwt().issuer())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getDisplayName())
                .claim("roles", roles)
                .claim("pwd", user.isPasswordChangeRequired())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .signWith(key)
                .compact();
        return new IssuedAccessToken(token, expiresAt);
    }

    /** Проверяет подпись, issuer и expiration. */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).requireIssuer(properties.jwt().issuer())
                    .build().parseSignedClaims(token).getPayload();
        } catch (RuntimeException ex) {
            throw new AuthenticationFailedException();
        }
    }

    public record IssuedAccessToken(String token, Instant expiresAt) {
    }
}
