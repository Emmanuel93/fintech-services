package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class CredentialNotFoundException extends DomainException {

    public CredentialNotFoundException(String identifier) {
        super("AUTH_CREDENTIAL_NOT_FOUND",
              "No credentials found for '" + identifier + "'");
    }
}
