package com.fintech.stp.domain.collection;

import com.fintech.shared.banking.ClabeCheckDigit;

import java.util.Objects;

/**
 * A collection CLABE in a company's STP range: {@code 646} (STP) + {@code 180} (plaza) + the company's 4-digit client
 * prefix + a 7-digit consecutive + the check digit. Value object: built from its parts and validated, or rejected.
 */
public final class CollectionClabe {

    public static final int CONSECUTIVE_DIGITS = 7;
    public static final long MAX_CONSECUTIVE = 9_999_999L;

    private final String value;

    private CollectionClabe(String value) {
        this.value = value;
    }

    /** The {@code consecutive}-th CLABE of a company's range. */
    public static CollectionClabe compose(String bankCode, String plazaCode, String clientPrefix, long consecutive) {
        requireDigits(bankCode, 3, "clave de banco");
        requireDigits(plazaCode, 3, "plaza");
        requireDigits(clientPrefix, 4, "prefijo de cliente STP");
        if (consecutive < 1 || consecutive > MAX_CONSECUTIVE) {
            throw new IllegalArgumentException("El consecutivo de CLABE está fuera del rango de la empresa: " + consecutive);
        }
        String body = bankCode + plazaCode + clientPrefix + String.format("%0" + CONSECUTIVE_DIGITS + "d", consecutive);
        return new CollectionClabe(ClabeCheckDigit.complete(body));
    }

    /** Whether {@code clabe} belongs to the range of the company with these codes. */
    public static boolean inRange(String clabe, String bankCode, String plazaCode, String clientPrefix) {
        return clabe != null && ClabeCheckDigit.isValid(clabe)
                && clabe.startsWith(bankCode + plazaCode + clientPrefix);
    }

    private static void requireDigits(String value, int length, String what) {
        if (value == null || value.length() != length || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("La " + what + " debe tener exactamente " + length + " dígitos: " + value);
        }
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CollectionClabe other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
