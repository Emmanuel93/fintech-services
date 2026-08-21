package com.fintech.accounting.domain;

import com.fintech.shared.exception.DomainException;

public class PostingRuleNotFoundException extends DomainException {
    public PostingRuleNotFoundException(String triggerEvent) {
        super("ACCOUNTING_POSTING_RULE_NOT_FOUND", "No posting rule for triggerEvent=" + triggerEvent);
    }
}
