package com.fintech.disbursement.domain;

import com.fintech.shared.exception.DomainException;

/** No existe la orden de desembolso consultada. */
public class DisbursementNotFoundException extends DomainException {

    public DisbursementNotFoundException(String message) {
        super("DISBURSEMENT_NOT_FOUND", message);
    }
}
