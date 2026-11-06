package com.fintech.creditportfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The minimum payment of a credit card, with the floor Banco de México sets (Circular 13/2011, 2.1).
 *
 * <p>The product sets its own share of the balance, but it can never ask for less than the greater of:
 * <ul>
 *   <li>1.5 % of the revolving balance without interest nor IVA, plus the interest and its IVA; and</li>
 *   <li>1.25 % of the credit line.</li>
 * </ul>
 * and never more than the revolving balance itself. Purchases in installments are not part of it: their installments
 * are added on top ("pago mínimo más compras a meses").
 *
 * <p>The same rule is written in statement-service (RevolvingStatementAssembler): the portfolio bills it and measures
 * delinquency against it, the statement prints it, and both must say the same amount.
 */
public final class CardMinimumPayment {

    static final BigDecimal BALANCE_SHARE = new BigDecimal("0.015");
    static final BigDecimal LINE_SHARE = new BigDecimal("0.0125");

    private CardMinimumPayment() {}

    /**
     * The minimum asked of the revolving balance.
     *
     * @param productMinimum the product's own minimum (its share of the balance); null when it sets none
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
        BigDecimal floor = onBalance.max(onLine);
        return nz(productMinimum).max(floor).min(balance).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
