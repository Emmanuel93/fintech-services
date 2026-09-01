package com.fintech.creditportfolio.application.port.in;

import java.util.UUID;

/**
 * El cliente salta un pago: la cuota se corre y <b>no genera mora</b>.
 *
 * <p>Es una opción <b>del cliente</b>, no del backoffice: la app le deja elegir qué compromiso se
 * salta, dentro del tope que el producto fija por ciclo. Es lo que la distingue de un programa de
 * apoyo, que lo otorga la institución y aplica masivamente.
 */
public interface SkipPaymentUseCase {

    record SaltarPago(UUID creditAccountId, UUID installmentId) {}

    class NoSePuedeSaltarException extends RuntimeException {
        public NoSePuedeSaltarException(String motivo) { super(motivo); }
    }

    void saltar(SaltarPago cmd);
}
