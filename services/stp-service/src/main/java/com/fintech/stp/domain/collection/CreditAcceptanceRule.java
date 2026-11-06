package com.fintech.stp.domain.collection;

import java.util.Optional;

/**
 * One reason to return a credit to its payer. Rules are independent and ordered by {@link CreditAcceptancePolicy}:
 * the first that finds a cause decides; none finding one means the credit is kept.
 */
@FunctionalInterface
public interface CreditAcceptanceRule {

    /** The cause to return {@code facts} with, or empty when this rule has nothing against it. */
    Optional<ReturnCause> evaluate(InboundCreditFacts facts, Optional<CollectionAccount> account);
}
