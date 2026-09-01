package com.fintech.disbursement.domain;

import com.fintech.shared.banking.ClabeCheckDigit;

/**
 * Dígito verificador de Banxico.
 *
 * <p><b>El algoritmo vive en {@code shared} (BK-49).</b> Ver la nota en la fachada equivalente de
 * {@code stp}: tres copias del mismo bucle divergen en cuanto alguien corrige un caso borde en una
 * sola.
 *
 * <p>Validar aquí evita despachar al proveedor una orden que va a rechazar de todos modos, y es lo
 * que permite que el rechazo trivial no dé la vuelta completa por Kafka (DB-04).
 */
public final class ClabeValidator {

    public static final int CLABE_LENGTH = ClabeCheckDigit.LONGITUD;

    private ClabeValidator() {
    }

    public static boolean isValid(String clabe) {
        return ClabeCheckDigit.esValida(clabe);
    }

    public static int checkDigit(String first17Digits) {
        return ClabeCheckDigit.de(first17Digits);
    }

    /**
     * Los tres primeros dígitos de una CLABE son la institución.
     *
     * <p>Devuelve {@code null} en vez de lanzar cuando la cuenta no es numérica: se invoca también
     * para rails que no son SPEI, donde la cuenta puede ser cualquier cosa.
     */
    public static Integer institutionOf(String clabe) {
        return ClabeCheckDigit.institucionDe(clabe);
    }
}
