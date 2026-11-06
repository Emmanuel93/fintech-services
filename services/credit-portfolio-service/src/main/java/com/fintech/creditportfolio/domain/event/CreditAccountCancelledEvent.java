package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An installment credit was cancelled because its only disbursement could not happen (D63): it never lent anything.
 * Portfolio gave the principal back and cancelled the plan; each service that booked something for it compensates.
 *
 * <ul>
 *   <li>charges reverses what it charged (the opening fee, its IVA, any interest) and closes its accrual;</li>
 *   <li>accounting reverses the memo of the authorized line and keeps a reversed fee out of the invoices;</li>
 *   <li>origination closes the application as a failed disbursement.</li>
 * </ul>
 *
 * @param contractId the application that became the credit (origination's id)
 * @param reason     {@code DISBURSEMENT_FAILED} or {@code DISBURSEMENT_RETURNED}
 * @param amount     the disbursement that never reached the borrower
 */
public record CreditAccountCancelledEvent(UUID creditAccountId,
                                          UUID contractId,
                                          UUID obligorPartyId,
                                          String productType,
                                          String productCode,
                                          String originUnitCode,
                                          UUID dispositionId,
                                          BigDecimal amount,
                                          String reason,
                                          String failureCode,
                                          Instant occurredOn) {}
