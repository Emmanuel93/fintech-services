package com.fintech.creditportfolio.domain.beneficiary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What a distributor may place to one client of a given type: the branch's policy, copied from sales-org.
 *
 * @param maxLoanAmount  the most a single placement may be
 * @param maxOutstanding the most the client may owe her across his live placements, the new one included
 * @param maxActiveLoans how many placements he may have alive at once, the new one included
 * @param maxTerm        the longest term, in fortnights
 */
public record ClientLimits(BigDecimal maxLoanAmount, BigDecimal maxOutstanding, Integer maxActiveLoans, Integer maxTerm) {

    public static final String AMOUNT_ABOVE_CLIENT_LIMIT = "AMOUNT_ABOVE_CLIENT_LIMIT";
    public static final String CLIENT_OUTSTANDING_LIMIT = "CLIENT_OUTSTANDING_LIMIT";
    public static final String CLIENT_ACTIVE_LOANS_LIMIT = "CLIENT_ACTIVE_LOANS_LIMIT";
    public static final String TERM_ABOVE_CLIENT_LIMIT = "TERM_ABOVE_CLIENT_LIMIT";

    public static final ClientLimits NONE = new ClientLimits(null, null, null, null);

    public boolean isEmpty() {
        return maxLoanAmount == null && maxOutstanding == null && maxActiveLoans == null && maxTerm == null;
    }

    /**
     * Why a placement does not fit this client, in the portfolio's own codes; empty when it fits.
     *
     * @param activeLoans how many live placements he already has with her
     * @param outstanding what he still owes her on them
     */
    public List<String> rejections(ClientType type, BigDecimal amount, Integer termPeriods, int activeLoans, BigDecimal outstanding) {
        List<String> reasons = new ArrayList<>();
        if (maxLoanAmount != null && amount != null && amount.compareTo(maxLoanAmount) > 0) {
            reasons.add(AMOUNT_ABOVE_CLIENT_LIMIT + ": cliente " + type + " hasta " + maxLoanAmount + " por préstamo, pidió " + amount);
        }
        if (maxActiveLoans != null && activeLoans + 1 > maxActiveLoans) {
            reasons.add(CLIENT_ACTIVE_LOANS_LIMIT + ": cliente " + type + " hasta " + maxActiveLoans + " préstamos vivos, ya tiene " + activeLoans);
        }
        if (maxOutstanding != null && amount != null) {
            BigDecimal owed = outstanding == null ? BigDecimal.ZERO : outstanding;
            if (owed.add(amount).compareTo(maxOutstanding) > 0) {
                reasons.add(CLIENT_OUTSTANDING_LIMIT + ": cliente " + type + " hasta " + maxOutstanding + " de saldo, debe " + owed
                        + " y pidió " + amount);
            }
        }
        if (maxTerm != null && termPeriods != null && termPeriods > maxTerm) {
            reasons.add(TERM_ABOVE_CLIENT_LIMIT + ": cliente " + type + " hasta " + maxTerm + " quincenas, pidió " + termPeriods);
        }
        return reasons;
    }

    /** What the client may still take: amount, loans and outstanding headroom (null where the limit is not set). */
    public BigDecimal amountHeadroom(BigDecimal outstanding) {
        BigDecimal byOutstanding = maxOutstanding == null ? null : maxOutstanding.subtract(outstanding == null ? BigDecimal.ZERO : outstanding).max(BigDecimal.ZERO);
        if (maxLoanAmount == null) return byOutstanding;
        return byOutstanding == null ? maxLoanAmount : maxLoanAmount.min(byOutstanding);
    }
}
