package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What charges reads of {@code credit-portfolio.credit-account-cancelled}: an installment credit whose only
 * disbursement could not happen (D63). It never lent anything, so nothing it was charged for stands.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountCancelledPayload(UUID creditAccountId,
                                            UUID dispositionId,
                                            BigDecimal amount,
                                            String reason,
                                            String failureCode,
                                            Instant occurredOn) {}
