package com.fintech.channelmobile.application;

import com.fintech.channelmobile.infrastructure.config.ChannelMobileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private final ChannelMobileProperties properties;
    private final OtpRateLimiter rateLimiter;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, OtpEntry> store = new ConcurrentHashMap<>();

    public OtpService(ChannelMobileProperties properties, OtpRateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    public String generate(String phone) {
        if (!rateLimiter.checkAndIncrement(phone)) {
            log.warn("OTP rate limit exceeded phone={}", phone);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Límite de envíos OTP alcanzado para este número. Intenta más tarde.");
        }

        // Ambiente bajo: almacenar devCode para flujo determinista end-to-end.
        // Así generate() y verify() usan el mismo código sin bypass. Sin devCode configurado no
        // hay ambiente bajo que valga: se emite un código real, como en producción.
        String code = (!properties.isOtpCodeValidation() && devCodeConfigurado() != null)
                ? devCodeConfigurado()
                : String.format("%06d", random.nextInt(1_000_000));

        Instant expiresAt = Instant.now()
                .plusSeconds(properties.getOtpExpiryMinutes() * 60L);
        store.put(phone, new OtpEntry(code, expiresAt, 0));

        log.info("[OTP] phone={} codeValidation={} expiresAt={}",
                phone, properties.isOtpCodeValidation(), expiresAt);
        return code;
    }

    public boolean verify(String phone, String code) {
        if (!properties.isOtpCodeValidation()) {
            String devCode = devCodeConfigurado();
            // Sin código de ambiente bajo configurado no hay atajo: no valida nada.
            //
            // Antes caía a un "123456" escrito en el código. `application-prod.yml` vacía
            // `otp-dev-code` justamente para desactivar el atajo, y ese respaldo lo volvía a
            // encender en silencio: bastaba que alguien desplegara con la validación apagada para
            // que un OTP público entrara en cualquier teléfono. Un valor por omisión que resucita
            // lo que la configuración apagó a propósito no es una comodidad, es una puerta.
            if (devCode == null) {
                return false;
            }
            // El entry puede no existir (nunca se llamó a send): en ambiente bajo se acepta igual,
            // para no obligar a scriptear el envío antes de cada alta.
            OtpEntry entry = store.get(phone);
            boolean match = devCode.equals(code);
            if (match && entry != null) store.remove(phone);
            return match;
        }

        OtpEntry entry = store.get(phone);
        if (entry == null) return false;

        if (entry.attempts() >= properties.getOtpMaxAttempts()) {
            store.remove(phone);
            return false;
        }

        if (!entry.isValid(code)) {
            store.put(phone, entry.incrementAttempts());
            return false;
        }

        store.remove(phone);
        return true;
    }

    /** El código de ambiente bajo tal como está configurado, o {@code null} si no lo está. */
    private String devCodeConfigurado() {
        String c = properties.getOtpDevCode();
        return (c != null && !c.isBlank()) ? c : null;
    }

    public boolean hasActive(String phone) {
        OtpEntry entry = store.get(phone);
        return entry != null && !entry.isExpired();
    }

    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        store.entrySet().removeIf(e -> e.getValue().isExpired());
    }
}
