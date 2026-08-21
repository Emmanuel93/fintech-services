package com.fintech.creditportfolio.application;

import java.math.BigDecimal;
import java.util.UUID;

public record ProcessAgreementExecutedCommand(
        String sourceEventId,
        UUID creditAccountId,
        String type,                  // RESTRUCTURE | QUITA_PARCIAL
        BigDecimal forgivenAmount,    // QUITA_PARCIAL only
        BigDecimal newNominalRate,    // RESTRUCTURE only
        Integer newTermMonths         // RESTRUCTURE only
) {}
