package com.fintech.stp.domain;

import com.fintech.shared.banking.ClabeCheckDigit;

/**
 * Valida y genera el dígito verificador de una CLABE.
 *
 * <p><b>El algoritmo vive en {@code shared} (BK-49).</b> Estaba escrito tres veces —aquí, en
 * {@code disbursement} y en {@code banking}— con la misma aritmética de Banxico y tres redacciones
 * distintas del mismo bucle. El día que alguien corrigiera un caso borde en una copia, las otras dos
 * seguirían aceptando lo que aquélla rechaza: el mismo número pasaría en un servicio y rebotaría en
 * otro.
 *
 * <p>Se conserva esta clase como fachada del dominio y no se borra: {@code StpDecouplingTest} exige
 * que este servicio se pueda extraer a otro repositorio sin tocar el resto, y el único import que
 * eso permite es {@code shared}.
 *
 * <p>Validar aquí evita un viaje completo a STP para una orden que va a rechazar de todos modos.
 */
public final class ClabeValidator {

    public static final int CLABE_LENGTH = ClabeCheckDigit.LONGITUD;

    private ClabeValidator() {
    }

    public static boolean isValid(String clabe) {
        return ClabeCheckDigit.esValida(clabe);
    }

    /** Calcula el verificador de los 17 primeros dígitos. */
    public static int checkDigit(String first17Digits) {
        return ClabeCheckDigit.de(first17Digits);
    }

    /** Completa una CLABE de 17 dígitos con su verificador. */
    public static String withCheckDigit(String first17Digits) {
        return ClabeCheckDigit.completar(first17Digits);
    }
}
