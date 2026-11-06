package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.OutboxMessage;

/**
 * Delivers one type of outbox message. The relay picks the handler by type, so a new kind of message plugs in without
 * touching the relay. Throwing leaves the message pending for a retry with backoff.
 */
public interface OutboxMessageHandler {

    String messageType();

    void handle(OutboxMessage message);
}
