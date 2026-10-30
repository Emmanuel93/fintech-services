package com.fintech.beneficiary.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Los secretos de la liga —token, código OTP, token de sesión— y cómo se guardan.
 *
 * <p><b>En base sólo vive el SHA-256, nunca el secreto.</b> Una copia de la tabla, un log de
 * consultas lentas o un respaldo mal custodiado no deben bastar para hacerse pasar por la
 * beneficiaria. Con el hash se puede comprobar lo que ella presenta, pero no reconstruirlo.
 *
 * <p>La comparación es en tiempo constante ({@link MessageDigest#isEqual}). Con {@code equals} el
 * tiempo de respuesta delata cuántos caracteres coinciden, y un token se puede ir adivinando
 * carácter por carácter midiendo latencias.
 */
public final class SecretHash {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretHash() {}

    /** SHA-256 en hexadecimal minúsculo: 64 caracteres, que es el ancho de las columnas. */
    public static String sha256Hex(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Toda JVM certificada trae SHA-256: si falta, el entorno está roto y no hay nada que
            // degradar con elegancia.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /**
     * El hash de un código OTP, atado a su liga.
     *
     * <p>Se mezcla con el id de la invitación porque el espacio de un OTP es de un millón: sin esa
     * sal, la tabla de los un millón de hashes posibles se calcula una vez y sirve para todas las
     * ligas. Con ella, cada liga es su propio espacio.
     */
    public static String otpHash(java.util.UUID inviteId, String code) {
        return sha256Hex(inviteId + ":" + code);
    }

    /** Compara dos hashes sin filtrar por el tiempo cuántos caracteres coinciden. */
    public static boolean matches(String expectedHex, String candidateHex) {
        if (expectedHex == null || candidateHex == null) return false;
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.US_ASCII),
                candidateHex.getBytes(StandardCharsets.US_ASCII));
    }

    /** 32 bytes aleatorios en base64 URL-safe sin relleno: viaja en una URL sin escaparse. */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Seis dígitos, con ceros a la izquierda: «004213» es tan válido como «913377». */
    public static String randomOtpCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
