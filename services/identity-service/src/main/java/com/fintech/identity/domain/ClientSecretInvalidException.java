package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class ClientSecretInvalidException extends DomainException {

    public ClientSecretInvalidException() {
        super("AUTH_CLIENT_SECRET_INVALID", "Invalid client credentials");
    }
}
