package com.fintech.creditportfolio.domain.beneficiary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the portfolio knows of a distributor's client: his type, his red-list marks, and how he stands on each line he
 * owes.
 *
 * @param loansGranted every placement he ever received (failed and reversed ones never happened)
 * @param loansPaid    the ones fully paid on a line without quebranto or quita
 * @param activeLoans  the ones still owed
 * @param outstanding  the principal he still owes across all of them
 */
public record BeneficiaryProfile(UUID beneficiaryPartyId, ClientType type, List<FlagView> flags,
                                 int loansGranted, int loansPaid, int activeLoans, BigDecimal outstanding,
                                 List<LineStanding> lines) {

    public boolean isRedListed() {
        return !flags.isEmpty();
    }

    /** The reasons, joined, for a rejection or a warning. */
    public String flagSummary() {
        return String.join(", ", flags.stream().map(f -> f.reason().name()).distinct().toList());
    }

    /** One red-list mark, derived or stored ({@code flagId} only for stored ones). */
    public record FlagView(UUID flagId, FlagReason reason, FlagSource source, Instant since, UUID distributorPartyId,
                           UUID creditAccountId, String detail, String comment) {}

    /** How he stands on one distributor's line. */
    public record LineStanding(UUID creditAccountId, UUID distributorPartyId, int activeLoans, BigDecimal outstanding,
                               boolean voucherOverdue, boolean distributorInArrears, boolean lineBlocked,
                               String lineBlockReason) {}

    public LineStanding on(UUID creditAccountId) {
        return lines.stream().filter(l -> l.creditAccountId().equals(creditAccountId)).findFirst()
                .orElse(new LineStanding(creditAccountId, null, 0, BigDecimal.ZERO, false, false, false, null));
    }
}
