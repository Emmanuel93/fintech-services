package com.fintech.charges.domain;

import com.fintech.shared.exception.DomainException;

public class ChargeRecordNotFoundException extends DomainException {
    public ChargeRecordNotFoundException(String chargeId) {
        super("CHARGES_RECORD_NOT_FOUND", "ChargeRecord not found: " + chargeId);
    }
}
