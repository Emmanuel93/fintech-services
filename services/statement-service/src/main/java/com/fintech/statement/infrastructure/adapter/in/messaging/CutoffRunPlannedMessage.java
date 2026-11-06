package com.fintech.statement.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Lo que se usa de {@code closing.cutoff-run-planned}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CutoffRunPlannedMessage(UUID runId, LocalDate businessDate, int totalUnits, List<Line> breakdown) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String productType, String productBehavior, String holderKind, int units) {}
}
