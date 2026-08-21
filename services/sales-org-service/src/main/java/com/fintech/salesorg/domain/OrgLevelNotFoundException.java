package com.fintech.salesorg.domain;

import com.fintech.shared.exception.DomainException;

public class OrgLevelNotFoundException extends DomainException {
    public OrgLevelNotFoundException(String ref) {
        super("ORG_LEVEL_NOT_FOUND", "Org level not found: " + ref);
    }
}
