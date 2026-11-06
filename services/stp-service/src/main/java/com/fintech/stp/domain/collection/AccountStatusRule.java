package com.fintech.stp.domain.collection;

import java.util.Optional;

/** The CLABE has to be one we issued and still receive on: unknown → cause 1, blocked → 2, closed → 3. */
public final class AccountStatusRule implements CreditAcceptanceRule {

    @Override
    public Optional<ReturnCause> evaluate(InboundCreditFacts facts, Optional<CollectionAccount> account) {
        if (account.isEmpty()) {
            return Optional.of(ReturnCause.CUENTA_INEXISTENTE);
        }
        return switch (account.get().status()) {
            case ACTIVE -> Optional.empty();
            case BLOCKED -> Optional.of(ReturnCause.CUENTA_BLOQUEADA);
            case CLOSED -> Optional.of(ReturnCause.CUENTA_CANCELADA);
        };
    }
}
