package com.fintech.collections.domain;

import java.math.BigDecimal;

/** Proposed new terms for a RESTRUCTURE agreement — stored as JSONB on CollectionAgreement.newTerms. */
public record RestructureTerms(
        BigDecimal newNominalRate,
        Integer newTermMonths,
        String newAmortizationType
) {}
