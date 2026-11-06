package com.fintech.stp.domain.collection;

import java.util.Optional;
import java.util.Set;

/**
 * Only the SPEI payment types a third party uses to pay a loan are kept; the rest are returned with cause 15. The
 * allowed set is configuration (the legacy connector ran on 1, 5, 19, 20, 21 and 22 against STP in production).
 */
public final class PaymentTypeRule implements CreditAcceptanceRule {

    private final Set<Integer> allowed;

    public PaymentTypeRule(Set<Integer> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    @Override
    public Optional<ReturnCause> evaluate(InboundCreditFacts facts, Optional<CollectionAccount> account) {
        if (facts.paymentType() == null || allowed.contains(facts.paymentType())) {
            return Optional.empty();
        }
        return Optional.of(ReturnCause.TIPO_DE_PAGO_ERRONEO);
    }
}
