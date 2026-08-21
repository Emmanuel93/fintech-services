package com.fintech.risk.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidPolicyStateException extends DomainException {
    public InvalidPolicyStateException(String message) {
        super("RISK_INVALID_POLICY_STATE", message);
    }
}
