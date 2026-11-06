package com.fintech.creditportfolio.domain.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * What a distributor earns on what her clients pay her, as the catalog declares it and the portfolio applies it: a
 * flat share of every installment (principal and interest), the same each fortnight, paid out of the placement's
 * interest and never out of its capital, smaller the later she delivers after the statement's due date, nothing past
 * the last tier. Netted from what she delivers against each statement, or paid out after the month closes.
 *
 * @param basis      {@code COLLECTED_PRINCIPAL_AND_INTEREST} (the installment without its IVA) or
 *                   {@code COLLECTED_TOTAL} (IVA included)
 * @param rate       her share when she delivers by the statement's due date
 * @param tiers      her share by the days she delivers after the due date, ascending from day 0; past the last tier's
 *                   day she earns nothing
 * @param settlement {@code NETTED} or {@code PAYOUT}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DistributorCommission(String basis, BigDecimal rate, List<Tier> tiers, String settlement) {

    public static final String COLLECTED_PRINCIPAL_AND_INTEREST = "COLLECTED_PRINCIPAL_AND_INTEREST";
    public static final String COLLECTED_TOTAL = "COLLECTED_TOTAL";
    public static final String NETTED = "NETTED";
    public static final String PAYOUT = "PAYOUT";
    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** Her share when she delivers {@code daysLate} days after the statement's due date. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tier(int daysLate, BigDecimal rate) {}

    public DistributorCommission {
        tiers = tiers == null ? List.of() : tiers.stream().sorted(Comparator.comparingInt(Tier::daysLate)).toList();
    }

    /** Whether her commission is deducted from what she delivers (the market's way) rather than paid to her apart. */
    public boolean isNetted() {
        return settlement == null || NETTED.equalsIgnoreCase(settlement);
    }

    /** Whether the share counts the installment's IVA as collected. */
    public boolean countsTax() {
        return COLLECTED_TOTAL.equalsIgnoreCase(basis);
    }

    /** Her share when she delivers {@code daysLate} days after the due date (0 or less: on time); zero past the ladder. */
    public BigDecimal rateFor(long daysLate) {
        if (tiers.isEmpty()) return daysLate <= 0 ? nz(rate) : BigDecimal.ZERO;
        BigDecimal applicable = BigDecimal.ZERO;
        boolean any = false;
        for (Tier t : tiers) {
            if (t.daysLate() <= Math.max(0, daysLate)) {
                applicable = nz(t.rate());
                any = true;
            } else {
                break;
            }
        }
        // Past the last tier's day she earns nothing: the ladder ends where it ends.
        if (any && Math.max(0, daysLate) > tiers.get(tiers.size() - 1).daysLate()) return BigDecimal.ZERO;
        return any ? applicable : BigDecimal.ZERO;
    }

    /**
     * What a flat commission scheduled at the on-time rate becomes when she delivers {@code daysLate} days after the
     * due date: scaled by the tier, to the cent.
     */
    public BigDecimal at(BigDecimal scheduledOnTime, long daysLate) {
        BigDecimal onTime = nz(rate);
        if (scheduledOnTime == null || onTime.signum() == 0) return BigDecimal.ZERO;
        BigDecimal tier = rateFor(daysLate);
        if (tier.compareTo(onTime) == 0) return scheduledOnTime.setScale(2, RoundingMode.HALF_UP);
        return scheduledOnTime.multiply(tier).divide(onTime, 2, RoundingMode.HALF_UP);
    }

    /** The commission the share sets on an installment, before the plan's interest caps it. */
    public BigDecimal shareOf(BigDecimal principal, BigDecimal interest, BigDecimal tax) {
        BigDecimal collected = nz(principal).add(nz(interest)).add(countsTax() ? nz(tax) : BigDecimal.ZERO);
        return collected.multiply(nz(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    /** The last day of the ladder: delivering after it earns nothing. */
    public int lastTierDay() {
        return tiers.isEmpty() ? 0 : tiers.get(tiers.size() - 1).daysLate();
    }

    static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    static boolean isCent(BigDecimal v) { return v != null && v.abs().compareTo(CENT) >= 0; }
}
