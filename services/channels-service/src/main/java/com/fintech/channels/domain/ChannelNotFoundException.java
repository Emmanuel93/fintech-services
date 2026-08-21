package com.fintech.channels.domain;

import com.fintech.shared.exception.DomainException;

public class ChannelNotFoundException extends DomainException {
    public ChannelNotFoundException(String id) {
        super("CHANNELS_CHANNEL_NOT_FOUND", "Channel not found: " + id);
    }
}
