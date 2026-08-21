package com.fintech.stp.domain;

import com.fintech.shared.exception.DomainException;

/** No hay llave vigente para la empresa. KY-06: no se firma con una llave expirada. */
public class SigningKeyNotAvailableException extends DomainException {

    public SigningKeyNotAvailableException(String message) {
        super("STP_SIGNING_KEY_UNAVAILABLE", message);
    }
}
