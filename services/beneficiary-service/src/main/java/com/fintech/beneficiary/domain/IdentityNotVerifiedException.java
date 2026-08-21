package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

import java.util.UUID;

/**
 * Se intentó aprobar una colocación cuya identidad no está comprobada.
 *
 * <p>No es un fallo técnico: es la regla del producto. Kredius no filtra por riesgo —eso lo decide
 * la distribuidora— pero <b>sí</b> por identidad, y el depósito no sale sin ella.
 */
public class IdentityNotVerifiedException extends DomainException {
    public IdentityNotVerifiedException(UUID placementId, IdentityDecision decision) {
        super("BENEFICIARY_IDENTITY_NOT_VERIFIED",
                "La colocación " + placementId + " no puede aprobarse: su identidad está en "
                        + decision + ". El depósito exige identidad comprobada.");
    }
}
