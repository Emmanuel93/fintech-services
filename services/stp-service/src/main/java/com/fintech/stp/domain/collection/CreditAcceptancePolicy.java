package com.fintech.stp.domain.collection;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides, while STP waits for the answer, whether a credit is kept or returned: an ordered chain of
 * {@link CreditAcceptanceRule}s, the first cause found wins. Pure Java: the rules are the whole decision.
 */
public final class CreditAcceptancePolicy {

    private final List<CreditAcceptanceRule> rules;

    public CreditAcceptancePolicy(List<CreditAcceptanceRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** The policy of the connector: complete data first, then the payment type, then the CLABE. */
    public static CreditAcceptancePolicy standard(Set<Integer> allowedPaymentTypes) {
        return new CreditAcceptancePolicy(List.of(
                new MandatoryDataRule(),
                new PaymentTypeRule(allowedPaymentTypes),
                new AccountStatusRule()));
    }

    /** Empty when the credit is kept; otherwise why it goes back to the payer. */
    public Optional<ReturnCause> evaluate(InboundCreditFacts facts, Optional<CollectionAccount> account) {
        for (CreditAcceptanceRule rule : rules) {
            Optional<ReturnCause> cause = rule.evaluate(facts, account);
            if (cause.isPresent()) {
                return cause;
            }
        }
        return Optional.empty();
    }
}
