package com.fintech.stp.domain;

import com.fintech.shared.exception.DomainException;

/** No existe una empresa activa con ese identificador ante STP. */
public class CompanyNotFoundException extends DomainException {

    public CompanyNotFoundException(String message) {
        super("STP_COMPANY_NOT_FOUND", message);
    }
}
