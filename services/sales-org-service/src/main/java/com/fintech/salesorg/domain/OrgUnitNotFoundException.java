package com.fintech.salesorg.domain;

import com.fintech.shared.exception.DomainException;

public class OrgUnitNotFoundException extends DomainException {
    public OrgUnitNotFoundException(String ref) {
        super("ORG_UNIT_NOT_FOUND", "Org unit not found: " + ref);
    }
}
