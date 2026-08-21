package com.fintech.channelmobile;

import com.fintech.channelmobile.application.OtpRateLimiter;
import com.fintech.channelmobile.infrastructure.config.ChannelMobileProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OtpRateLimiterTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    ChannelMobileProperties properties;
    OtpRateLimiter rateLimiter;

    static final String PHONE = "5512345678";
    static final String KEY = "otp:rate:" + PHONE;

    @BeforeEach
    void setUp() {
        properties = new ChannelMobileProperties();
        properties.setOtpMaxSendsPerWindow(3);
        properties.setOtpSendWindowMinutes(60);
        given(redis.opsForValue()).willReturn(valueOps);
        rateLimiter = new OtpRateLimiter(redis, properties);
    }

    @Test
    void firstSend_setsWindowExpiry_returnsTrue() {
        given(valueOps.increment(KEY)).willReturn(1L);

        boolean result = rateLimiter.checkAndIncrement(PHONE);

        assertThat(result).isTrue();
        then(redis).should().expire(KEY, Duration.ofMinutes(60));
    }

    @Test
    void secondSend_withinLimit_doesNotResetExpiry_returnsTrue() {
        given(valueOps.increment(KEY)).willReturn(2L);

        boolean result = rateLimiter.checkAndIncrement(PHONE);

        assertThat(result).isTrue();
        then(redis).should(never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void thirdSend_atExactLimit_returnsTrue() {
        given(valueOps.increment(KEY)).willReturn(3L);

        assertThat(rateLimiter.checkAndIncrement(PHONE)).isTrue();
    }

    @Test
    void fourthSend_overLimit_returnsFalse() {
        given(valueOps.increment(KEY)).willReturn(4L);

        assertThat(rateLimiter.checkAndIncrement(PHONE)).isFalse();
    }

    @Test
    void nullCountFromRedis_failOpen_returnsTrue() {
        given(valueOps.increment(KEY)).willReturn(null);

        assertThat(rateLimiter.checkAndIncrement(PHONE)).isTrue();
    }

    @Test
    void differentPhones_useIndependentKeys() {
        given(valueOps.increment("otp:rate:5511111111")).willReturn(1L);
        given(valueOps.increment("otp:rate:5522222222")).willReturn(4L);

        assertThat(rateLimiter.checkAndIncrement("5511111111")).isTrue();
        assertThat(rateLimiter.checkAndIncrement("5522222222")).isFalse();
    }
}
