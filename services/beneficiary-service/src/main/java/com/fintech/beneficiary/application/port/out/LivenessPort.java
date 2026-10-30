package com.fintech.beneficiary.application.port.out;

/**
 * La prueba de vida.
 *
 * <p>Sin proveedor (no hay Incode), el adaptador <b>no decide nada</b>: guarda la selfie y declara
 * que la revisión es manual. Un humano la compara contra la INE en la mesa de KYC. Inventar un
 * «vivo: sí» en un stub sería exactamente el tipo de verificación falsa que la regla de identidad
 * existe para impedir.
 */
public interface LivenessPort {

    Result capture(byte[] selfie, String contentType);

    /** {@code review = MANUAL} mientras no haya proveedor; un proveedor real devolvería su puntaje. */
    record Result(boolean captured, String review) {}
}
