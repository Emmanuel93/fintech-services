package com.fintech.channelmobile;

import com.fintech.channelmobile.application.OtpRateLimiter;
import com.fintech.channelmobile.application.OtpService;
import com.fintech.channelmobile.infrastructure.config.ChannelMobileProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock OtpRateLimiter rateLimiter;

    ChannelMobileProperties properties;
    OtpService otpService;

    static final String PHONE = "5512345678";

    @BeforeEach
    void setUp() {
        properties = new ChannelMobileProperties();
        properties.setOtpExpiryMinutes(5);
        properties.setOtpMaxAttempts(3);
        properties.setOtpCodeValidation(true);
        otpService = new OtpService(properties, rateLimiter);
    }

    // ── generate ──────────────────────────────────────────────────────────

    @Test
    void generate_rateLimitOk_storesActiveOtp() {
        given(rateLimiter.checkAndIncrement(PHONE)).willReturn(true);

        String code = otpService.generate(PHONE);

        assertThat(code).matches("\\d{6}");
        assertThat(otpService.hasActive(PHONE)).isTrue();
    }

    @Test
    void generate_rateLimitExceeded_throws429() {
        given(rateLimiter.checkAndIncrement(PHONE)).willReturn(false);

        assertThatThrownBy(() -> otpService.generate(PHONE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
    }

    // ── verify — modo producción (otpCodeValidation = true) ───────────────

    @Test
    void verify_productionMode_correctCode_returnsTrue_andEvicts() {
        given(rateLimiter.checkAndIncrement(PHONE)).willReturn(true);
        String code = otpService.generate(PHONE);

        assertThat(otpService.verify(PHONE, code)).isTrue();
        assertThat(otpService.hasActive(PHONE)).isFalse();
    }

    @Test
    void verify_productionMode_wrongCode_returnsFalse() {
        given(rateLimiter.checkAndIncrement(PHONE)).willReturn(true);
        otpService.generate(PHONE);

        assertThat(otpService.verify(PHONE, "000000")).isFalse();
        assertThat(otpService.hasActive(PHONE)).isTrue();
    }

    @Test
    void verify_productionMode_maxAttemptsReached_evictsEntry() {
        properties.setOtpMaxAttempts(2);
        given(rateLimiter.checkAndIncrement(PHONE)).willReturn(true);
        otpService.generate(PHONE);

        otpService.verify(PHONE, "111111"); // intento 1 — falla
        otpService.verify(PHONE, "222222"); // intento 2 — falla y alcanza límite

        // La siguiente llamada no encuentra entrada (fue eviccionada)
        assertThat(otpService.verify(PHONE, "333333")).isFalse();
        assertThat(otpService.hasActive(PHONE)).isFalse();
    }

    @Test
    void verify_productionMode_noActiveOtp_returnsFalse() {
        assertThat(otpService.verify(PHONE, "123456")).isFalse();
    }

    // ── verify — modo dev (otpCodeValidation = false) ─────────────────────

    @Test
    void verify_devMode_correctDevCode_returnsTrue() {
        properties.setOtpCodeValidation(false);
        properties.setOtpDevCode("123456");

        assertThat(otpService.verify(PHONE, "123456")).isTrue();
    }

    @Test
    void verify_devMode_wrongCode_returnsFalse() {
        properties.setOtpCodeValidation(false);
        properties.setOtpDevCode("123456");

        assertThat(otpService.verify(PHONE, "000000")).isFalse();
    }

    @Test
    void verify_devMode_nullDevCode_returnsFalse() {
        properties.setOtpCodeValidation(false);
        properties.setOtpDevCode(null);

        assertThat(otpService.verify(PHONE, "123456")).isFalse();
    }

    @Test
    void verify_devMode_emptyDevCode_returnsFalse() {
        properties.setOtpCodeValidation(false);
        properties.setOtpDevCode("");

        assertThat(otpService.verify(PHONE, "")).isFalse();
    }

    @Test
    void verify_devMode_doesNotRequireActiveOtp() {
        properties.setOtpCodeValidation(false);
        properties.setOtpDevCode("123456");

        // No OTP generado previamente — debe validar igual
        assertThat(otpService.verify(PHONE, "123456")).isTrue();
    }

    // ── hasActive ─────────────────────────────────────────────────────────

    @Test
    void hasActive_afterGenerate_returnsTrue() {
        given(rateLimiter.checkAndIncrement(PHONE)).willReturn(true);
        otpService.generate(PHONE);

        assertThat(otpService.hasActive(PHONE)).isTrue();
    }

    @Test
    void hasActive_withoutGenerate_returnsFalse() {
        assertThat(otpService.hasActive("9900000000")).isFalse();
    }
}
