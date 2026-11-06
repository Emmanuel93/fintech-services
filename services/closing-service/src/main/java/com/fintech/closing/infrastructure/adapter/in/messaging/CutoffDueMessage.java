package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/** Lo que el consumidor del corte necesita de {@code closing.cutoff-due}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CutoffDueMessage(UUID runId, UUID unitId, UUID creditAccountId, String productType,
                               LocalDate businessDate) {}
