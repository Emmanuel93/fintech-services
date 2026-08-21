package com.fintech.origination.domain;

import com.fintech.shared.exception.DomainException;

public class CooldownActiveException extends DomainException {
    public CooldownActiveException(String productType, int cooldownDays) {
        super("ORIGINATION_COOLDOWN_ACTIVE",
                "Cannot re-apply for " + productType + ": cooldown period of "
                + cooldownDays + " days has not elapsed since last rejection (UW-06)");
    }
}
