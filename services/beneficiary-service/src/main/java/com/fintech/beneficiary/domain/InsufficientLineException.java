package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

import java.math.BigDecimal;

/**
 * El monto supera lo que al distribuidor le queda de línea.
 *
 * <p>Se revisa al crear la colocación aunque la línea <b>no se aparte hasta aprobar</b>: no es un
 * apartado, es cortesía. Mandarle una liga a alguien que va a hacer todo su KYC para que la
 * aprobación termine rebotando por cupo sería gastarle el tiempo a una persona que ni siquiera es
 * cliente.
 *
 * <p>La autoridad definitiva sigue siendo credit-portfolio al momento de la disposición: entre
 * invitar y aprobar pueden pasar días y otras colocaciones.
 */
public class InsufficientLineException extends DomainException {
    public InsufficientLineException(BigDecimal amount, BigDecimal availableLine) {
        super("BENEFICIARY_INSUFFICIENT_LINE",
                "El monto " + amount + " supera tu línea disponible de " + availableLine);
    }
}
