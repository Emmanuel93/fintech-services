package com.fintech.stp.domain.collection;

import java.util.Optional;

/** Without a positive amount, a tracking key and a beneficiary CLABE there is nothing that can be applied. */
public final class MandatoryDataRule implements CreditAcceptanceRule {

    @Override
    public Optional<ReturnCause> evaluate(InboundCreditFacts facts, Optional<CollectionAccount> account) {
        boolean complete = facts.amount() != null && facts.amount().signum() > 0
                && facts.trackingKey() != null && !facts.trackingKey().isBlank()
                && facts.beneficiaryClabe() != null && !facts.beneficiaryClabe().isBlank()
                && facts.operationDate() != null;
        return complete ? Optional.empty() : Optional.of(ReturnCause.FALTA_INFORMACION);
    }
}
