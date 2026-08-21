package com.fintech.channelmobile.application;

import com.fintech.channelmobile.infrastructure.config.ChannelMobileProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OtpRateLimiter {

    private static final String KEY_PREFIX = "otp:rate:";

    private final StringRedisTemplate redis;
    private final ChannelMobileProperties properties;

    public OtpRateLimiter(StringRedisTemplate redis, ChannelMobileProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * Incrementa el contador de envíos OTP del número en la ventana configurada.
     * Retorna false si se superó el límite, true si aún puede enviar.
     */
    public boolean checkAndIncrement(String phone) {
        String key = KEY_PREFIX + phone;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // Primer envío en la ventana: establece el TTL para que el contador se resetee automáticamente
            redis.expire(key, Duration.ofMinutes(properties.getOtpSendWindowMinutes()));
        }
        return count == null || count <= properties.getOtpMaxSendsPerWindow();
    }
}
