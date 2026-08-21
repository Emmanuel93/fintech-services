package com.fintech.disbursement.domain;

import com.fintech.shared.exception.DomainException;

/**
 * La instrucción de pago no es procesable: falta un dato obligatorio o uno excede lo que el rail
 * admite.
 *
 * <p>Existe porque la ruta de Kafka no pasa por Bean Validation. Sin esta comprobación, un
 * {@code concepto} de 60 caracteres no falla al recibirlo sino al hacer commit — y entonces el
 * desembolso acaba en el DLT en vez de rechazarse con un motivo legible.
 */
public class InvalidDisbursementInstructionException extends DomainException {

    public InvalidDisbursementInstructionException(String message) {
        super("DISBURSEMENT_INVALID_INSTRUCTION", message);
    }
}
