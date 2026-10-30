package com.fintech.beneficiary.domain;

/**
 * El celular como se le muestra a quien no debe verlo entero: {@code 55 •• •• 90 37}.
 *
 * <p>Se enmascara en el servidor y el crudo no se serializa nunca. Que el cliente reciba el dato y
 * decida taparlo sería confiar la privacidad de una persona a una decisión de UI.
 */
public final class PhoneMask {

    private PhoneMask() {}

    public static String of(String phone) {
        if (phone == null || phone.length() != 10) return "•• •• •• •• ••";
        return phone.substring(0, 2) + " •• •• " + phone.substring(6, 8) + " " + phone.substring(8);
    }
}
