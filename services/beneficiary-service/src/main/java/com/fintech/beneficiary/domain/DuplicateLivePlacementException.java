package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

/**
 * Ese celular ya tiene una colocación viva con este distribuidor.
 *
 * <p>Se bloquea **por distribuidor**, no globalmente: dos distribuidores distintos pueden querer
 * colocarle a la misma persona, y eso es un negocio legítimo, no un fraude. Lo que no tiene
 * sentido es mandarle dos ligas al mismo número por el mismo remitente — la segunda invalidaría
 * la primera y la persona no sabría cuál abrir.
 */
public class DuplicateLivePlacementException extends DomainException {
    public DuplicateLivePlacementException(String phone) {
        super("BENEFICIARY_DUPLICATE_LIVE_PLACEMENT",
                "Ya existe una colocación viva para el celular " + phone);
    }
}
