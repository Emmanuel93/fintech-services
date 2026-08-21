package com.fintech.risk.domain;

import com.fintech.shared.exception.DomainException;

public class RiskProfileNotFoundException extends DomainException {
    public RiskProfileNotFoundException(String id) {
        super("RISK_PROFILE_NOT_FOUND", "RiskProfile not found: " + id);
    }
}
