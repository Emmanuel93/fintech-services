package com.fintech.stp.domain;

/**
 * Valida y genera el dígito verificador de una CLABE de 18 dígitos.
 *
 * <p>Algoritmo de Banxico: cada uno de los primeros 17 dígitos se multiplica por su ponderación
 * {@code {3,7,1}} cíclica, se toma el módulo 10 de cada producto, se suman, y el verificador es
 * {@code (10 - suma % 10) % 10}.
 *
 * <p>Validar aquí evita un viaje completo a STP para una orden que va a rechazar de todos modos.
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
        return checkDigit(clabe.substring(0, CLABE_LENGTH - 1)) == Character.getNumericValue(clabe.charAt(CLABE_LENGTH - 1));
    }

    /** Calcula el verificador de los 17 primeros dígitos. */
    public static int checkDigit(String first17Digits) {
        if (first17Digits == null || first17Digits.length() != WEIGHTS.length
                || !first17Digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Se esperan exactamente 17 dígitos para calcular el verificador");
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (Character.getNumericValue(first17Digits.charAt(i)) * WEIGHTS[i]) % 10;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** Completa una CLABE de 17 dígitos con su verificador. */
    public static String withCheckDigit(String first17Digits) {
        return first17Digits + checkDigit(first17Digits);
    }
}
