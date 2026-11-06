package com.fintech.salesorg.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What a distributor may place to one client of a given type: the four limits of the placement policy.
 *
 * <p>Every field may be {@code null}: a limit a unit does not declare is inherited from its branch. Resolution walks
 * the tree from the root down ({@link #overriddenBy}); a distributor's own limits can only tighten what she inherits
 * ({@link #tightenedBy}), never widen it.
 *
 * @param maxLoanAmount  the most a single placement may be
 * @param maxOutstanding the most the client may owe her across all his live placements, including the new one
 * @param maxActiveLoans how many placements he may have alive at once, including the new one
 * @param maxTerm        the longest term, in fortnights
 */
public record ClientLimits(BigDecimal maxLoanAmount, BigDecimal maxOutstanding, Integer maxActiveLoans, Integer maxTerm) {

    public static final ClientLimits NONE = new ClientLimits(null, null, null, null);

    public boolean isEmpty() {
        return maxLoanAmount == null && maxOutstanding == null && maxActiveLoans == null && maxTerm == null;
    }

    /** Every limit declared: what a policy must be once it is resolved for a placement. */
    public boolean isComplete() {
        return maxLoanAmount != null && maxOutstanding != null && maxActiveLoans != null && maxTerm != null;
    }

    /** The child's declared limits replace these; what the child leaves out stays inherited. */
    public ClientLimits overriddenBy(ClientLimits child) {
        if (child == null) return this;
        return new ClientLimits(
                child.maxLoanAmount != null ? child.maxLoanAmount : maxLoanAmount,
                child.maxOutstanding != null ? child.maxOutstanding : maxOutstanding,
                child.maxActiveLoans != null ? child.maxActiveLoans : maxActiveLoans,
                child.maxTerm != null ? child.maxTerm : maxTerm);
    }

    /** The stricter of each limit: a distributor's own policy can only narrow what her branch allows. */
    public ClientLimits tightenedBy(ClientLimits child) {
        if (child == null) return this;
        return new ClientLimits(
                min(maxLoanAmount, child.maxLoanAmount),
                min(maxOutstanding, child.maxOutstanding),
                min(maxActiveLoans, child.maxActiveLoans),
                min(maxTerm, child.maxTerm));
    }

    /** The limits here that are wider than the inherited ones: what a distributor is not allowed to declare. */
    public List<String> widerThan(ClientLimits inherited) {
        List<String> wider = new ArrayList<>();
        if (exceeds(maxLoanAmount, inherited.maxLoanAmount)) wider.add("maxLoanAmount " + maxLoanAmount + " > " + inherited.maxLoanAmount);
        if (exceeds(maxOutstanding, inherited.maxOutstanding)) wider.add("maxOutstanding " + maxOutstanding + " > " + inherited.maxOutstanding);
        if (exceeds(maxActiveLoans, inherited.maxActiveLoans)) wider.add("maxActiveLoans " + maxActiveLoans + " > " + inherited.maxActiveLoans);
        if (exceeds(maxTerm, inherited.maxTerm)) wider.add("maxTerm " + maxTerm + " > " + inherited.maxTerm);
        return wider;
    }

    /**
     * What does not fit the product that defines the line: the policy narrows the product's envelope, it never
     * widens it, and its own numbers must make sense together.
     */
    public List<String> violations(ProductEnvelope product) {
        List<String> errors = new ArrayList<>();
        if (maxLoanAmount != null) {
            if (maxLoanAmount.signum() <= 0) errors.add("maxLoanAmount debe ser positivo");
            if (product.maxAmount() != null && maxLoanAmount.compareTo(product.maxAmount()) > 0)
                errors.add("maxLoanAmount " + maxLoanAmount + " rebasa el máximo del producto " + product.maxAmount());
            if (product.minAmount() != null && maxLoanAmount.compareTo(product.minAmount()) < 0)
                errors.add("maxLoanAmount " + maxLoanAmount + " queda bajo el mínimo del producto " + product.minAmount());
        }
        if (maxOutstanding != null) {
            if (maxOutstanding.signum() <= 0) errors.add("maxOutstanding debe ser positivo");
            if (maxLoanAmount != null && maxOutstanding.compareTo(maxLoanAmount) < 0)
                errors.add("maxOutstanding " + maxOutstanding + " no alcanza para un préstamo de " + maxLoanAmount);
        }
        if (maxActiveLoans != null && maxActiveLoans < 1) errors.add("maxActiveLoans debe ser al menos 1");
        if (maxTerm != null) {
            if (maxTerm < 1) errors.add("maxTerm debe ser al menos 1");
            if (product.maxTerm() != null && maxTerm > product.maxTerm())
                errors.add("maxTerm " + maxTerm + " rebasa el plazo máximo del producto " + product.maxTerm());
            if (product.minTerm() != null && maxTerm < product.minTerm())
                errors.add("maxTerm " + maxTerm + " queda bajo el plazo mínimo del producto " + product.minTerm());
        }
        return errors;
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.min(b);
    }

    private static Integer min(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    private static boolean exceeds(BigDecimal own, BigDecimal inherited) {
        return own != null && inherited != null && own.compareTo(inherited) > 0;
    }

    private static boolean exceeds(Integer own, Integer inherited) {
        return own != null && inherited != null && own > inherited;
    }
}
