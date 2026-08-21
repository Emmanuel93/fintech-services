package com.fintech.risk.domain;

import com.fintech.shared.exception.DomainException;

/**
 * PR-03: there is no ACTIVE ProvisionPolicy for a productType, so no rate can be applied.
 * Thrown/logged explicitly — Risk never invents a provision rate silently.
 */
public class MissingProvisionPolicyException extends DomainException {
    public MissingProvisionPolicyException(String productType) {
        super("RISK_MISSING_PROVISION_POLICY",
                "No ACTIVE ProvisionPolicy for productType=" + productType + " — cannot compute provision");
    }
}
