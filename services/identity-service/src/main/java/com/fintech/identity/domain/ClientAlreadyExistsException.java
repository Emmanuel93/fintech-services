package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class ClientAlreadyExistsException extends DomainException {

    public ClientAlreadyExistsException(String clientId) {
        super("AUTH_CLIENT_ALREADY_EXISTS", "Client already exists: " + clientId);
    }
}
