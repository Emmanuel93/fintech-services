package com.fintech.collections.application;

import com.fintech.collections.domain.AgreementType;
import com.fintech.collections.domain.RestructureTerms;
import java.math.BigDecimal;
import java.util.UUID;

public record ProposeAgreementCommand(
        UUID caseId,
        AgreementType type,
        BigDecimal forgivenAmount,     // QUITA_PARCIAL only
        RestructureTerms newTerms,     // RESTRUCTURE only
        String proposedBy              // the staff session that proposes it
) {
    public ProposeAgreementCommand(UUID caseId, AgreementType type, BigDecimal forgivenAmount, RestructureTerms newTerms) {
        this(caseId, type, forgivenAmount, newTerms, null);
    }
}
