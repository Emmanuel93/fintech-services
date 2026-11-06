package com.fintech.statement.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/** Lo que se usa de {@code closing.cutoff-run-completed}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CutoffRunCompletedMessage(UUID runId, LocalDate businessDate, String status, int plannedUnits,
                                        int doneUnits, int failedUnits, int skippedUnits) {}
