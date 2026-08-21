package com.fintech.identity.infrastructure.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.port.out.TokenRepository;
import com.fintech.identity.domain.AuthToken;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RedisTokenRepository implements TokenRepository {

    // Key schema:
    //   token:jti:{jti}             → partyId (string)      TTL = accessTokenExpiryMinutes
    //   token:refresh:{hash}        → JSON(AuthToken)        TTL = refreshTokenExpiryDays
    //   token:party:{partyId}       → Set of members         TTL = refreshTokenExpiryDays
    //     members: "jti:{jti}" | "refresh:{hash}"

    private static final String JTI_PREFIX     = "token:jti:";
    private static final String REFRESH_PREFIX = "token:refresh:";
    private static final String PARTY_PREFIX   = "token:party:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties properties;

    public RedisTokenRepository(StringRedisTemplate redis,
                                ObjectMapper objectMapper,
                                AuthProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public boolean isTokenActive(String tokenId) {
        return Boolean.TRUE.equals(redis.hasKey(JTI_PREFIX + tokenId));
    }

    @Override
    public Optional<AuthToken> findByRefreshTokenHash(String refreshTokenHash) {
        String json = redis.opsForValue().get(REFRESH_PREFIX + refreshTokenHash);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, AuthToken.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    @Override
    public AuthToken save(AuthToken token) {
        if (token.isRevoked()) {
            removeToken(token);
        } else {
            storeToken(token);
        }
        return token;
    }

    @Override
    public void revokeAllByPartyId(UUID partyId) {
        String partyKey = PARTY_PREFIX + partyId;
        Set<String> members = redis.opsForSet().members(partyKey);
        if (members == null || members.isEmpty()) return;

        List<String> keysToDelete = new ArrayList<>();
        for (String member : members) {
            if (member.startsWith("jti:")) {
                keysToDelete.add(JTI_PREFIX + member.substring(4));
            } else if (member.startsWith("refresh:")) {
                keysToDelete.add(REFRESH_PREFIX + member.substring(8));
            }
        }
        keysToDelete.add(partyKey);
        redis.delete(keysToDelete);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void storeToken(AuthToken token) {
        long refreshTtlSeconds = Duration.between(Instant.now(), token.getExpiresAt()).getSeconds();
        if (refreshTtlSeconds <= 0) return;

        long jtiTtlSeconds = (long) properties.getAccessTokenExpiryMinutes() * 60;

        try {
            String json = objectMapper.writeValueAsString(token);

            redis.opsForValue().set(
                    JTI_PREFIX + token.getTokenId(),
                    token.getPartyId().toString(),
                    jtiTtlSeconds, TimeUnit.SECONDS);

            redis.opsForValue().set(
                    REFRESH_PREFIX + token.getRefreshTokenHash(),
                    json,
                    refreshTtlSeconds, TimeUnit.SECONDS);

            String partyKey = PARTY_PREFIX + token.getPartyId();
            redis.opsForSet().add(partyKey,
                    "jti:" + token.getTokenId(),
                    "refresh:" + token.getRefreshTokenHash());
            redis.expire(partyKey, refreshTtlSeconds, TimeUnit.SECONDS);

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize AuthToken to Redis", e);
        }
    }

    private void removeToken(AuthToken token) {
        redis.delete(JTI_PREFIX + token.getTokenId());
        redis.delete(REFRESH_PREFIX + token.getRefreshTokenHash());
        redis.opsForSet().remove(
                PARTY_PREFIX + token.getPartyId(),
                "jti:" + token.getTokenId(),
                "refresh:" + token.getRefreshTokenHash());
    }
}
