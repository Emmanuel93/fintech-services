package com.fintech.beneficiary.domain;

/**
 * El veredicto de identidad sobre una beneficiaria.
 *
 * <p>Es <b>independiente del avance comercial</b> de la colocación: son dos juicios distintos y de
 * dos responsables distintos. La distribuidora decide si le presta —eso es riesgo, y es suyo—;
 * Kredius decide si la persona es quien dice ser. Mezclarlos fue lo que hizo que hasta hoy
 * {@code IdentityStatus} se derivara del estado de la colocación y no hubiera dónde poner el juicio
 * de un analista.
 */
public enum IdentityDecision {
    /** Nadie ha dictaminado. Es el estado de todo lo que entra a la mesa. */
    PENDING,
    /** Es quien dice ser. Es lo único que habilita el depósito. */
    VERIFIED,
    /** No se pudo comprobar. Siempre con motivo. */
    REJECTED
}
