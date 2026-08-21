package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

import java.util.UUID;

/** Se intentó mover una colocación a un estado al que no se llega desde donde está. */
public class InvalidPlacementTransitionException extends DomainException {

    private final transient PlacementStatus from;
    private final transient PlacementStatus to;

    public InvalidPlacementTransitionException(UUID placementId, PlacementStatus from, PlacementStatus to) {
        super("BENEFICIARY_INVALID_PLACEMENT_TRANSITION",
                "La colocación " + placementId + " no puede pasar de " + from + " a " + to
                        + ". Destinos válidos desde " + from + ": "
                        + (from.isTerminal() ? "ninguno, es un estado terminal" : from.allowedTargets()));
        this.from = from;
        this.to   = to;
    }

    public PlacementStatus getFrom() { return from; }
    public PlacementStatus getTo()   { return to; }
}
