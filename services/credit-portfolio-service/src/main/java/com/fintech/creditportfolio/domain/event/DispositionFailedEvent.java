package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A disposition's payment did not happen, for good, and was compensated at once (D63): the money never left
 * ({@code FAILED}) or the beneficiary's bank sent it back ({@code RETURNED}). The principal and the available credit
 * are back; its plan was cancelled. When it was an installment credit's only disbursement the credit itself was
 * cancelled too ({@code accountCancelled}), and {@code credit-portfolio.credit-account-cancelled} follows.
 *
 * <p>Who listens: notifications tells the borrower; anyone that keeps the disposition's state learns it ended here.
 *
 * @param outcome     {@code FAILED} or {@code RETURNED}
 * @param failureCode the last attempt's failure, as disbursement said it
 * @param attempts    how many attempts its payment had
 */
public record DispositionFailedEvent(UUID dispositionId,
                                     UUID creditAccountId,
                                     UUID obligorPartyId,
                                     String productType,
                                     String dispositionType,
                                     BigDecimal amount,
                                     String outcome,
                                     String failureCode,
                                     String failureReason,
                                     int attempts,
                                     boolean accountCancelled,
                                     BigDecimal availableCredit,
                                     Instant occurredOn) {}
