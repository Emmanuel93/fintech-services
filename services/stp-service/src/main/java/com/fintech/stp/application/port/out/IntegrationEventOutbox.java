package com.fintech.stp.application.port.out;

import java.util.UUID;

/**
 * Integration events written in the same transaction as the fact they announce, published by the outbox relay once
 * it commits. Publishing inside the transaction left facts circulating that the database could still contradict.
 */
public interface IntegrationEventOutbox {

    void enqueue(String topic, String key, UUID aggregateId, Object event);
}
