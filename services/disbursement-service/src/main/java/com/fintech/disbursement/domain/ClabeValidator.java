package com.fintech.disbursement.domain;

/**
 * Dígito verificador de Banxico. Validar aquí evita despachar al proveedor una orden que va a
 * rechazar de todos modos, y es lo que permite que el rechazo trivial no dé la vuelta completa
 * por Kafka (DB-04).
 */
public final class ClabeValidator {

    public static final int CLABE_LENGTH = 18;
    private static final int[] WEIGHTS = {3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7};

    private ClabeValidator() {
    }

    public static boolean isValid(String clabe) {
        if (clabe == null || clabe.length() != CLABE_LENGTH || !clabe.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return checkDigit(clabe.substring(0, CLABE_LENGTH - 1))
                == Character.getNumericValue(clabe.charAt(CLABE_LENGTH - 1));
    }

    public static int checkDigit(String first17Digits) {
        if (first17Digits == null || first17Digits.length() != WEIGHTS.length
                || !first17Digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Se esperan exactamente 17 dígitos");
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (Character.getNumericValue(first17Digits.charAt(i)) * WEIGHTS[i]) % 10;
        }
        return (10 - (sum % 10)) % 10;
    }

    /**
     * Los tres primeros dígitos de una CLABE son la institución.
     *
     * <p>Devuelve {@code null} en vez de lanzar cuando la cuenta no es numérica: este método se
     * invoca también para rails que no son SPEI, donde la cuenta puede ser cualquier cosa. Lanzar
     * aquí convertiría un dato opcional en un HTTP 500.
     */
    public static Integer institutionOf(String clabe) {
        if (clabe == null || clabe.length() < 3) {
            return null;
        }
        String prefix = clabe.substring(0, 3);
        return prefix.chars().allMatch(Character::isDigit) ? Integer.valueOf(prefix) : null;
    }
}
