package com.fintech.commission.domain;

import java.math.BigDecimal;

/**
 * The taxes on a commission: the IVA the beneficiary invoices on top of it, and what a company withholds when it pays a
 * natural person (ISR on the commission, by regime, and a fraction of the IVA).
 *
 * @param ivaRate              IVA on the commission (16 % nationally)
 * @param isrNaturalPersonRate ISR withheld from a natural person (10 %)
 * @param isrResicoRate        ISR withheld from a natural person in the simplified trust regime (1.25 %)
 * @param ivaWithheldFraction  the part of the IVA withheld from a natural person (two thirds)
 */
public record TaxRules(BigDecimal ivaRate, BigDecimal isrNaturalPersonRate, BigDecimal isrResicoRate,
                       BigDecimal ivaWithheldFraction) {

    public static final TaxRules MEXICO = new TaxRules(new BigDecimal("0.16"), new BigDecimal("0.10"),
            new BigDecimal("0.0125"), new BigDecimal("0.666666666667"));
}
