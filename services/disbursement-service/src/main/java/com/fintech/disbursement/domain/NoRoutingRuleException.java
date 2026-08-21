package com.fintech.disbursement.domain;

import com.fintech.shared.exception.DomainException;

/** No hay regla de routing que cubra esta orden. */
public class NoRoutingRuleException extends DomainException {

    public NoRoutingRuleException(String message) {
        super("DISBURSEMENT_NO_ROUTING_RULE", message);
    }
}
