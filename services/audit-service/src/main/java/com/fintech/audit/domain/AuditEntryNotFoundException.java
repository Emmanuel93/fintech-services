package com.fintech.audit.domain;

import com.fintech.shared.exception.DomainException;

public class AuditEntryNotFoundException extends DomainException {
    public AuditEntryNotFoundException(String id) {
        super("AUDIT_ENTRY_NOT_FOUND", "Audit entry not found: " + id);
    }
}
