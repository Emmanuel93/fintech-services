package com.fintech.disbursement.domain;

import com.fintech.shared.exception.DomainException;

/** No se pudo resolver la empresa. Nunca se adivina (DB-07). */
public class UnresolvedCompanyException extends DomainException {

    public UnresolvedCompanyException(String message) {
        super("DISBURSEMENT_UNRESOLVED_COMPANY", message);
    }
}
