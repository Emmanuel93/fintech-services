package com.fintech.invoicing.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FiscalProfileUpdatedPayload(
        UUID partyId,
        /** El id con el que cartera y contabilidad conocen al cliente. Nulo en eventos antiguos. */
        UUID prospectId,
        String partyType,
        String rfc,
        String taxName,
        String taxRegime,
        String taxZipCode,
        String cfdiUse
) {}
