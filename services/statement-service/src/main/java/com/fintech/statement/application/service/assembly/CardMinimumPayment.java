package com.fintech.statement.application.service.assembly;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The minimum payment of a credit card, with the floor Banco de México sets (Circular 13/2011, 2.1): the product's
 * own share of the balance, but never less than the greater of 1.5 % of the revolving balance without interest nor
 * IVA plus the interest and its IVA, and 1.25 % of the credit line; never more than the revolving balance.
 *
 * <p>The same rule is written in credit-portfolio-service (CardMinimumPayment): the portfolio bills it and measures
 * delinquency against it, this service prints it, and both must say the same amount.
 */
public final class CardMinimumPayment {

    static final BigDecimal BALANCE_SHARE = new BigDecimal("0.015");
    static final BigDecimal LINE_SHARE = new BigDecimal("0.0125");

    private CardMinimumPayment() {}

    /**
     * @param productMinimum the product's own minimum (what closing sealed); null when it sets none
     * @param revolving      the balance at the cutoff not in installment plans
     * @param accrued        interest, IVA and other charges owed at the cutoff (balance − principal)
     * @param creditLimit    the credit line; null when unknown
     */
    public static BigDecimal revolvingMinimum(BigDecimal productMinimum, BigDecimal revolving, BigDecimal accrued,
                                              BigDecimal creditLimit) {
        BigDecimal balance = nz(revolving).max(BigDecimal.ZERO);
        BigDecimal charges = nz(accrued).max(BigDecimal.ZERO).min(balance);
        BigDecimal principal = balance.subtract(charges);
        BigDecimal onBalance = principal.multiply(BALANCE_SHARE).add(charges);
        BigDecimal onLine = nz(creditLimit).multiply(LINE_SHARE);
        return nz(productMinimum).max(onBalance.max(onLine)).min(balance).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
