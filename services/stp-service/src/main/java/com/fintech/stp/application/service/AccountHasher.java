package com.fintech.stp.application.service;

import com.fintech.stp.application.StpProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Enmascara la cuenta del beneficiario antes de persistirla.
 *
 * <p>HMAC-SHA256 con salt, no SHA-256 pelado como el legado: el espacio de CLABEs es enumerable
 * (18 dígitos con estructura conocida), así que un hash sin salt se rompe con un diccionario.
 */
@Component
public class AccountHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] salt;

    public AccountHasher(StpProperties properties) {
        this.salt = properties.getAccountHashSalt().getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String account) {
        if (account == null || account.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(salt, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(account.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular el HMAC de la cuenta", e);
        }
    }

    /** Para logs: sólo los últimos 4 dígitos. */
    public static String mask(String account) {
        if (account == null || account.length() < 4) {
            return "****";
        }
        return "****" + account.substring(account.length() - 4);
    }
}
