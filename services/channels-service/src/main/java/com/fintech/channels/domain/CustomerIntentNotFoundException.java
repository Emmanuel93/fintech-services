package com.fintech.channels.domain;

import com.fintech.shared.exception.DomainException;

public class CustomerIntentNotFoundException extends DomainException {
    public CustomerIntentNotFoundException(String id) {
        super("CHANNELS_INTENT_NOT_FOUND", "CustomerIntent not found: " + id);
    }
}
