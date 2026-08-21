package com.fintech.channels.domain;

import com.fintech.shared.exception.DomainException;

public class SessionNotFoundException extends DomainException {
    public SessionNotFoundException(String id) {
        super("CHANNELS_SESSION_NOT_FOUND", "Session not found: " + id);
    }
}
