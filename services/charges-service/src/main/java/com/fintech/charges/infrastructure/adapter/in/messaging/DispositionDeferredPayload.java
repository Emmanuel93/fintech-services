package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Forma de {@code credit-portfolio.disposition-deferred}. La ventana es lo que decide cuánto reversar. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispositionDeferredPayload(UUID dispositionId,
                                         UUID creditAccountId,
                                         UUID obligorPartyId,
                                         BigDecimal amount,
                                         int termPeriods,
                                         BigDecimal nominalRate,
                                         LocalDate revolvingDesde,
                                         LocalDate revolvingHasta) {}
