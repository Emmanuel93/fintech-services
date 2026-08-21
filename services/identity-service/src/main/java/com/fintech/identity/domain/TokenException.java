package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class TokenException extends DomainException {

    public TokenException(String reason) {
        super("AUTH_TOKEN_INVALID", reason);
    }
}
