package com.fintech.origination.domain;

import com.fintech.shared.exception.DomainException;

public class ProspectNotFoundException extends DomainException {

    public ProspectNotFoundException(String prospectId) {
        super("PROSPECT_NOT_FOUND", "Prospect not found: " + prospectId);
    }
}
