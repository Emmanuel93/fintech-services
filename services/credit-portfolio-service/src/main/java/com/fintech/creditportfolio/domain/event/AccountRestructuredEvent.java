package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A restructure agreement took effect on this account: new rate and, for an installment loan, a new plan.
 *
 * <p>Consumed by {@code charges}, which accrues the new rate from {@code effectiveDate} on. Before this event the
 * agreement only changed the account's record: charges kept accruing the old rate and the plan stayed the old one
 * while the statement printed the new rate (2 Nov, E04).
 *
 * @param eventId          the agreement's id: the same restructure delivered twice is one fact
 * @param effectiveDate    the business day the agreement took effect
 * @param principal        the principal the new plan amortizes
 * @param newTermPeriods   installments of the new plan (null on a revolving line, which has no plan of its own)
 * @param firstDueDate     first installment of the new plan (null on a revolving line)
 * @param replacedInstallments how many unpaid installments the new plan superseded
 */
public record AccountRestructuredEvent(String eventId,
                                       UUID creditAccountId,
                                       UUID obligorPartyId,
                                       UUID agreementId,
                                       BigDecimal previousNominalRate,
                                       BigDecimal newNominalRate,
                                       BigDecimal principal,
                                       Integer newTermPeriods,
                                       LocalDate firstDueDate,
                                       int replacedInstallments,
                                       LocalDate effectiveDate) {}
