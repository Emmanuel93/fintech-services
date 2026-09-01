package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/** Forma de {@code credit-portfolio.relief-granted}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReliefGrantedPayload(UUID reliefProgramId,
                                   String programName,
                                   String reason,
                                   UUID creditAccountId,
                                   int deferredPeriods,
                                   LocalDate reliefValidTo) {}
