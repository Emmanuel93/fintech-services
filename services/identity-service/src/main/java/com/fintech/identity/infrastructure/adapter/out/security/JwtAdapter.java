package com.fintech.identity.infrastructure.adapter.out.security;

import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.port.out.TokenClaims;
import com.fintech.identity.application.port.out.TokenPort;
import com.fintech.identity.domain.Channel;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtAdapter implements TokenPort {

    static final String ISSUER = "identity-service";

    private final AuthProperties properties;
    private volatile PrivateKey cachedPrivateKey;
    private volatile PublicKey cachedPublicKey;

    public JwtAdapter(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public String generateAccessToken(UUID subject, List<String> roles, String deviceId, Channel channel) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject.toString())
                .issuer(ISSUER)
                .claim("roles", roles)
                .claim("deviceId", deviceId)
                .claim("channel", channel.name())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(
                        properties.getAccessTokenExpiryMinutes(), ChronoUnit.MINUTES)))
                .signWith(privateKey())
                .compact();
    }

    @Override
    public Optional<TokenClaims> validateToken(String token) {
        try {
            Claims payload = Jwts.parser()
                    .verifyWith(publicKey())
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) payload.get("roles");

            return Optional.of(new TokenClaims(
                    payload.getSubject(),
                    payload.getId(),
                    roles != null ? roles : List.of(),
                    payload.get("deviceId", String.class),
                    Channel.fromClaim(payload.get("channel", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String extractJti(String token) {
        return validateToken(token)
                .map(TokenClaims::jti)
                .orElseThrow(() -> new IllegalArgumentException("Cannot extract jti from invalid token"));
    }

    private PrivateKey privateKey() {
        if (cachedPrivateKey == null) {
            synchronized (this) {
                if (cachedPrivateKey == null) {
                    cachedPrivateKey = loadPrivateKey(properties.getPrivateKeyPath());
                }
            }
        }
        return cachedPrivateKey;
    }

    private PublicKey publicKey() {
        if (cachedPublicKey == null) {
            synchronized (this) {
                if (cachedPublicKey == null) {
                    cachedPublicKey = loadPublicKey(properties.getPublicKeyPath());
                }
            }
        }
        return cachedPublicKey;
    }

    static PrivateKey loadPrivateKey(String path) {
        String pem = readFile(path)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] bytes = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load RSA private key from " + path, e);
        }
    }

    static PublicKey loadPublicKey(String path) {
        String pem = readFile(path)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] bytes = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load RSA public key from " + path, e);
        }
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read PEM file: " + path, e);
        }
    }
}
