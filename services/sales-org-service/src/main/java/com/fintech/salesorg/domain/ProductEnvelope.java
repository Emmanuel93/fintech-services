package com.fintech.salesorg.domain;

import java.math.BigDecimal;

/**
 * The ranges of the product that defines the distributor line: a placement policy must fit inside them.
 *
 * <p>The product fixes the line (rate, cutoffs, 12 to 24 fortnights, $1,000 to $10,000 per placement); the branch
 * narrows what each kind of client may take from it. Read from the catalog when a policy is saved, never copied.
 */
public record ProductEnvelope(String productCode, BigDecimal minAmount, BigDecimal maxAmount, Integer minTerm, Integer maxTerm) {
}
