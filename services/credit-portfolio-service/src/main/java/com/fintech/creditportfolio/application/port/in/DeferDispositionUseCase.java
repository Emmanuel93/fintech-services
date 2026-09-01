package com.fintech.creditportfolio.application.port.in;

import java.util.UUID;

/**
 * El titular difiere una compra ya hecha: deja de ser exigible entera en el corte y pasa a plazos.
 *
 * <p>Es lo contrario de la colocación de un distribuidor, donde el plazo se fija <b>al</b> colocar.
 * Aquí la compra ya ocurrió y el cliente decide después, desde la app, antes de que corte el ciclo.
 */
public interface DeferDispositionUseCase {

    /**
     * @param termPeriods a cuántos pagos; nulo cae al plazo por defecto del producto
     */
    record DiferirCompra(UUID creditAccountId, UUID dispositionId, Integer termPeriods) {}

    /** Se rechaza con motivo legible: fuera de ventana, plazo inválido, producto que no difiere. */
    class NoSePuedeDiferirException extends RuntimeException {
        public NoSePuedeDiferirException(String motivo) { super(motivo); }
    }

    void diferir(DiferirCompra cmd);
}
