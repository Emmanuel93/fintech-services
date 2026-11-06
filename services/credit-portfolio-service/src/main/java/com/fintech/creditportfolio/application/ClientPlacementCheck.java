package com.fintech.creditportfolio.application;

import com.fintech.creditportfolio.domain.CreditAccount;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The client-side checks of a placement on a distributor line: the red list and the branch's limits for his type.
 *
 * <p>Returns the portfolio's reason to refuse, or {@code null} when he may take it. Only a line that pays a
 * distributor asks; a direct credit's amount is the score's business.
 */
public interface ClientPlacementCheck {

    String reject(CreditAccount line, UUID beneficiaryPartyId, BigDecimal amount, Integer termPeriods);
}
