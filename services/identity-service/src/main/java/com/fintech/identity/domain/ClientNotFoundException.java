package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class ClientNotFoundException extends DomainException {

    public ClientNotFoundException(String clientId) {
        super("AUTH_CLIENT_NOT_FOUND", "Client not found: " + clientId);
    }
}
