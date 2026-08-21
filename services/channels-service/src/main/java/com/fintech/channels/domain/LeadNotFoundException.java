package com.fintech.channels.domain;

import com.fintech.shared.exception.DomainException;

public class LeadNotFoundException extends DomainException {
    public LeadNotFoundException(String id) {
        super("CHANNELS_LEAD_NOT_FOUND", "Lead not found: " + id);
    }
}
