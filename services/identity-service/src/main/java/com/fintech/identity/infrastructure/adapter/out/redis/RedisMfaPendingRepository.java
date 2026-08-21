package com.fintech.identity.infrastructure.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.port.out.MfaPendingRepository;
import com.fintech.identity.application.port.out.MfaPendingRepository.MfaPendingData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
class RedisMfaPendingRepository implements MfaPendingRepository {

    // Key: mfa:pending:{token} → JSON{partyId, username}, TTL = mfaPendingTtlSeconds
    private static final String PREFIX = "mfa:pending:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties properties;

    RedisMfaPendingRepository(StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               AuthProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String store(UUID partyId, String deviceId, String username) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String value = serialize(new MfaPendingData(partyId, username));
        redis.opsForValue().set(
                PREFIX + token,
                value,
                properties.getMfaPendingTtlSeconds(),
                TimeUnit.SECONDS);
        return token;
    }

    @Override
    public Optional<MfaPendingData> consume(String mfaToken) {
        String key = PREFIX + mfaToken;
        String value = redis.opsForValue().getAndDelete(key);
        if (value == null) return Optional.empty();
        return Optional.ofNullable(deserialize(value));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private String serialize(MfaPendingData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MfaPendingData", e);
        }
    }

    private MfaPendingData deserialize(String json) {
        try {
            return objectMapper.readValue(json, MfaPendingData.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
