package com.fintech.commission.domain;

import com.fintech.shared.exception.DomainException;

public class CommissionPolicyNotFoundException extends DomainException {
    public CommissionPolicyNotFoundException(String id) {
        super("COMMISSION_POLICY_NOT_FOUND", "CommissionPolicy not found: " + id);
    }
}
