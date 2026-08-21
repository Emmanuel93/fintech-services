package com.fintech.disbursement.domain;

import com.fintech.shared.exception.DomainException;

/** Transición no permitida: los estados terminales son inmutables (DB-01). */
public class InvalidDisbursementStateException extends DomainException {

    public InvalidDisbursementStateException(String message) {
        super("DISBURSEMENT_INVALID_STATE", message);
    }
}
