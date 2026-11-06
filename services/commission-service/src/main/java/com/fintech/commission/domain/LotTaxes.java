package com.fintech.commission.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A lot's taxes: the commission (subtotal) and its IVA, the ISR and IVA withheld from a natural person, and the net the
 * SPEI pays. The withholdings are computed on the lot, the document the beneficiary invoices.
 */
public record LotTaxes(BigDecimal subtotal, BigDecimal iva, BigDecimal isrWithheld, BigDecimal ivaWithheld) {

    /** The taxes of a lot of {@code subtotal} with {@code iva}, paid to {@code beneficiary}. */
    public static LotTaxes of(BigDecimal subtotal, BigDecimal iva, BeneficiaryProfile beneficiary, TaxRules rules) {
        if (!beneficiary.isNaturalPerson()) {
            return new LotTaxes(subtotal, iva, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }
        BigDecimal isrRate = beneficiary.isResico() ? rules.isrResicoRate() : rules.isrNaturalPersonRate();
        return new LotTaxes(subtotal, iva,
                subtotal.multiply(isrRate).setScale(2, RoundingMode.HALF_UP),
                iva.multiply(rules.ivaWithheldFraction()).setScale(2, RoundingMode.HALF_UP));
    }

    /** What is owed to the beneficiary: the commission and its IVA. */
    public BigDecimal gross() { return subtotal.add(iva); }

    /** What the SPEI pays: the gross less what is withheld (and paid to the SAT on the beneficiary's behalf). */
    public BigDecimal net() { return gross().subtract(isrWithheld).subtract(ivaWithheld); }
}
