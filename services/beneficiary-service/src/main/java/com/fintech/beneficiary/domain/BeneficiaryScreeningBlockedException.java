package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

import java.util.UUID;

/**
 * Se intentó aprobar una colocación cuya beneficiaria no está despejada de listas.
 *
 * <p>Tiene tipo propio y no reutiliza {@link IdentityNotVerifiedException} porque <b>la respuesta
 * operativa es distinta</b>: a una identidad en revisión se le pide mejor evidencia —otra foto, otra
 * prueba de vida— y la distribuidora puede ayudar a conseguirla. Ante una coincidencia de listas
 * <b>no hay nada que la distribuidora ni la beneficiaria puedan hacer</b>: lo resuelve cumplimiento,
 * o no se resuelve. Mandar los dos casos por el mismo error haría que la pantalla pidiera documentos
 * para algo que ningún documento arregla.
 */
public class BeneficiaryScreeningBlockedException extends DomainException {
    public BeneficiaryScreeningBlockedException(UUID placementId, ScreeningVerdict verdict) {
        super("BENEFICIARY_SCREENING_BLOCKED",
                "La colocación " + placementId + " no puede aprobarse: " + verdict.blockReason()
                        + " (listas: " + verdict.status() + "). Lo resuelve cumplimiento.");
    }
}
