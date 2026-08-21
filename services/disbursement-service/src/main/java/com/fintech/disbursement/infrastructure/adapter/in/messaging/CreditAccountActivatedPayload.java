package com.fintech.disbursement.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Forma de {@code credit-portfolio.credit-account-activated}, recortada a lo que hace falta para
 * pagar.
 *
 * <p>Es un <strong>hecho</strong>, no un comando: dice que el crédito se activó, no que haya que
 * desembolsar. Este servicio decide por su cuenta que un {@code disbursementInstruction} presente
 * significa que hay dinero que sacar; los otros nueve consumidores del mismo hecho deciden otra cosa.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} no es decorativo: el evento trae quince
 * campos más de crédito que aquí no interesan y que pueden cambiar sin romper este adaptador.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        UUID creditAccountId,
        String eventId,
        Instant occurredOn,
        DisbursementInstruction disbursementInstruction
) {

    /** Bloque anidado. Ausente = no hay nada que pagar y el evento se ignora. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisbursementInstruction(
            UUID dispositionId,
            UUID companyId,
            String dispositionType,
            BigDecimal amount,
            String currency,
            String beneficiaryName,
            String beneficiaryAccount,
            String beneficiaryAccountType,
            String beneficiaryTaxId,
            Integer beneficiaryInstitution,
            Long numericReference,
            String concept
    ) {}
}
