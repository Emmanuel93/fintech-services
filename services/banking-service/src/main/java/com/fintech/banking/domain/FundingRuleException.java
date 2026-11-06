package com.fintech.banking.domain;

/** A funding instruction the rules do not allow; the message says why, in words the operator can act on. */
public class FundingRuleException extends RuntimeException {

    public FundingRuleException(String message) {
        super(message);
    }
}
