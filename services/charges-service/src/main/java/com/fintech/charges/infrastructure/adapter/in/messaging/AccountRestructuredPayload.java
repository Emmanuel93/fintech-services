package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** credit-portfolio.account-restructured: what charges needs of it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountRestructuredPayload(String eventId, UUID creditAccountId, BigDecimal previousNominalRate,
                                         BigDecimal newNominalRate, LocalDate effectiveDate) {}
