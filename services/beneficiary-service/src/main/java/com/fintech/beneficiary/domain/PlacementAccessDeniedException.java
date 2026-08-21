package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

import java.util.UUID;

/**
 * Un distribuidor intentó mover la colocación de otro.
 *
 * <p>Es 403 y no 404 a propósito: el id de una colocación se lo da el gateway a partir de un JWT
 * válido, así que quien llega aquí ya está autenticado. Devolver 404 escondería un error de
 * integración real detrás de un mensaje falso.
 */
public class PlacementAccessDeniedException extends DomainException {
    public PlacementAccessDeniedException(UUID placementId, UUID distributorPartyId) {
        super("BENEFICIARY_PLACEMENT_FORBIDDEN",
                "La colocación " + placementId + " no pertenece al distribuidor " + distributorPartyId);
    }
}
