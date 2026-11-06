package com.fintech.creditproduct.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What a distributor earns on what her clients pay her, and how it is settled. Product configuration, versioned with
 * the product and published on activation: the portfolio schedules each placement's commission from it, nets it from
 * what she delivers, and the statement prints the ladder.
 *
 * <p>The market's rule: a share of everything collected (principal and interest), the same every fortnight, paid out
 * of the placement's interest and never out of its capital; smaller the later she pays after the statement's due date
 * (the tiers), nothing past the last tier.
 *
 * @param basis      {@code COLLECTED_PRINCIPAL_AND_INTEREST} (the installment without its IVA) or
 *                   {@code COLLECTED_TOTAL} (the installment as the client pays it, IVA included)
 * @param rate       her share when she pays by the statement's due date
 * @param tiers      her share by the days she pays after the due date, ascending; the first tier is day 0 (on time);
 *                   past the last tier's day she earns nothing
 * @param settlement {@code NETTED} (deducted from what she delivers against the statement) or {@code PAYOUT} (paid to
 *                   her by transfer after the month closes)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DistributorCommission(String basis, BigDecimal rate, List<Tier> tiers, String settlement) {

    public static final String COLLECTED_PRINCIPAL_AND_INTEREST = "COLLECTED_PRINCIPAL_AND_INTEREST";
    public static final String COLLECTED_TOTAL = "COLLECTED_TOTAL";
    public static final String NETTED = "NETTED";
    public static final String PAYOUT = "PAYOUT";

    /** Her share when she pays {@code daysLate} days after the statement's due date. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tier(int daysLate, BigDecimal rate) {}

    public DistributorCommission {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    /** The business's ladder: 18 % of what she collects on time, 14 % a day late, 11 % two, 7 % three, nothing after. */
    public static DistributorCommission marketLadder() {
        return new DistributorCommission(COLLECTED_TOTAL, new BigDecimal("0.18"),
                List.of(new Tier(0, new BigDecimal("0.18")), new Tier(1, new BigDecimal("0.14")),
                        new Tier(2, new BigDecimal("0.11")), new Tier(3, new BigDecimal("0.07"))), NETTED);
    }

    /** Why the configuration cannot be used, if it cannot. */
    public Optional<String> inconsistency() {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            return Optional.of("rate must be between 0 and 1");
        }
        if (basis != null && !COLLECTED_PRINCIPAL_AND_INTEREST.equals(basis) && !COLLECTED_TOTAL.equals(basis)) {
            return Optional.of("basis must be COLLECTED_PRINCIPAL_AND_INTEREST or COLLECTED_TOTAL");
        }
        if (settlement != null && !NETTED.equals(settlement) && !PAYOUT.equals(settlement)) {
            return Optional.of("settlement must be NETTED or PAYOUT");
        }
        if (!tiers.isEmpty()) {
            if (tiers.get(0).daysLate() != 0) return Optional.of("the first tier must be day 0, the payment on time");
            if (tiers.get(0).rate() == null || tiers.get(0).rate().compareTo(rate) != 0) {
                return Optional.of("the first tier's rate must be the rate");
            }
            List<Tier> sorted = tiers.stream().sorted(Comparator.comparingInt(Tier::daysLate)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                Tier t = sorted.get(i);
                if (t.daysLate() <= sorted.get(i - 1).daysLate()) return Optional.of("tiers must have distinct days");
                if (t.rate() == null || t.rate().signum() < 0 || t.rate().compareTo(sorted.get(i - 1).rate()) > 0) {
                    return Optional.of("a later tier cannot pay more than an earlier one");
                }
            }
        }
        return Optional.empty();
    }
}
