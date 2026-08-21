package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

/**
 * Se pidió algo que todavía no existe porque el flujo no ha llegado ahí.
 *
 * <p>El caso que la motiva es el reporte de buró: mientras la beneficiaria no termine su
 * verificación no hay historial que entregar, y devolver un reporte vacío sería peor que no
 * devolver nada — un desglose en ceros se lee como historial limpio, y esa confusión cuesta
 * dinero.
 *
 * <p>Es 409 y no 422: no hay nada malo en la petición, sólo llegó antes de tiempo. El mismo
 * request va a funcionar en cuanto el estado avance.
 */
public class PlacementNotReadyException extends DomainException {

    public PlacementNotReadyException(String message) {
        super("PLACEMENT_NOT_READY", message);
    }
}
