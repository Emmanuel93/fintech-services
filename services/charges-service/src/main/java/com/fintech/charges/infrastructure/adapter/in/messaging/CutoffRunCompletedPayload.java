package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/** Inbound de closing ({@code closing.cutoff-run-completed}): el corte de ese día terminó. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CutoffRunCompletedPayload(LocalDate businessDate, String status) {}
