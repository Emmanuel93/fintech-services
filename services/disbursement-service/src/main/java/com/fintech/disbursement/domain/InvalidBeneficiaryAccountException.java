package com.fintech.disbursement.domain;

import com.fintech.shared.exception.DomainException;

/** La cuenta del beneficiario no pasa la validación local (DB-04). */
public class InvalidBeneficiaryAccountException extends DomainException {

    public InvalidBeneficiaryAccountException(String message) {
        super("DISBURSEMENT_INVALID_BENEFICIARY_ACCOUNT", message);
    }
}
