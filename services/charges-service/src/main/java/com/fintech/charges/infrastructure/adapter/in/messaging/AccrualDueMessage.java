package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/** {@code charges.accrual-due}: una cuenta que devengar en el cierre de un día. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccrualDueMessage(LocalDate businessDate, UUID creditAccountId) {}
