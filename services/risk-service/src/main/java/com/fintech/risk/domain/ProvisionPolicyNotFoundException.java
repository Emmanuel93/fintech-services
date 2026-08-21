package com.fintech.risk.domain;

import com.fintech.shared.exception.DomainException;

public class ProvisionPolicyNotFoundException extends DomainException {
    public ProvisionPolicyNotFoundException(String id) {
        super("RISK_PROVISION_POLICY_NOT_FOUND", "ProvisionPolicy not found: " + id);
    }
}
