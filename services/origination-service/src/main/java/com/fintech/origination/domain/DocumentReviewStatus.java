package com.fintech.origination.domain;

/**
 * El estado de dictamen de un documento del expediente.
 *
 * <p>Nace en {@link #PENDING_REVIEW} en cuanto hay archivo: un documento entregado y no revisado no
 * es lo mismo que uno ausente, y el expediente tiene que poder distinguirlos para que el analista
 * sepa qué le falta por hacer y qué le falta por recibir.
 */
public enum DocumentReviewStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED;

    public boolean isResolved() {
        return this != PENDING_REVIEW;
    }
}
