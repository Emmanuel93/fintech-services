package com.fintech.disbursement.application;

import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.Rail;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Instrucción de pago, ya traducida a vocabulario de payouts.
 *
 * <p>Es la <strong>única</strong> forma de entrar al núcleo. Los tres listeners ACL y el controlador
 * REST de ingesta construyen esto; el núcleo no sabe cuál de ellos lo llamó.
 *
 * @param sourceSystem     quién origina ({@code credit-portfolio}, {@code wallet}, {@code api}…)
 * @param sourceReference  identificador del emisor. Cadena <strong>opaca</strong>: no se interpreta
 * @param sourceEventId    clave de idempotencia; con {@code sourceSystem} y {@code sourceType} forma
 *                         la única restricción de unicidad de negocio (DB-02)
 * @param sourceCompanyKey clave de empresa del emisor, a resolver contra {@code company_mappings}
 * @param companyId        empresa ya resuelta. Si viene, gana sobre {@code sourceCompanyKey}
 * @param sourceMetadata   contexto del emisor que se devuelve en eco. Opaco
 */
public record RequestDisbursementCommand(
        String sourceSystem,
        DisbursementSource sourceType,
        String sourceReference,
        String sourceEventId,
        String sourceCompanyKey,
        UUID companyId,
        Map<String, String> sourceMetadata,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryAccountType,
        String beneficiaryTaxId,
        Integer beneficiaryInstitution,
        BigDecimal amount,
        String currency,
        String concept,
        Long numericReference,
        Rail rail,
        String correlationId
) {
    public Rail railOrDefault() { return rail != null ? rail : Rail.SPEI; }
    public String currencyOrDefault() { return currency != null && !currency.isBlank() ? currency : "MXN"; }
    public Map<String, String> metadataOrEmpty() { return sourceMetadata != null ? sourceMetadata : Map.of(); }
}
