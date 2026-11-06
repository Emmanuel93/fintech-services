package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/** Inbound de cartera ({@code credit-portfolio.day-closed}): cerró el día con la mora al día siguiente. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioDayClosedPayload(LocalDate businessDate, LocalDate delinquencyAsOf) {}
