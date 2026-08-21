package com.fintech.stp.domain;

import com.fintech.shared.exception.DomainException;

/** La cuenta del beneficiario no pasa la validación local. */
public class InvalidBeneficiaryAccountException extends DomainException {

    public InvalidBeneficiaryAccountException(String message) {
        super("STP_INVALID_BENEFICIARY_ACCOUNT", message);
    }
}
