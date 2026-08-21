package com.fintech.commission.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidCommissionRecordStateException extends DomainException {
    public InvalidCommissionRecordStateException(String message) {
        super("COMMISSION_INVALID_RECORD_STATE", message);
    }
}
