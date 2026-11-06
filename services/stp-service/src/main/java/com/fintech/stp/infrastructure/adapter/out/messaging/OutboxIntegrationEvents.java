package com.fintech.stp.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.stp.application.port.out.IntegrationEventOutbox;
import com.fintech.stp.application.port.out.OutboxMessageHandler;
import com.fintech.stp.application.port.out.OutboxMessageRepository;
import com.fintech.stp.domain.OutboxMessage;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Integration events through the transactional outbox: written in the transaction of the fact they announce, and
 * published by the relay afterwards, synchronously, so a broker that does not take the event leaves it pending for
 * a retry instead of losing it.
 */
@Component
public class OutboxIntegrationEvents implements IntegrationEventOutbox, OutboxMessageHandler {

    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final OutboxMessageRepository outbox;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxIntegrationEvents(OutboxMessageRepository outbox, KafkaTemplate<String, Object> kafkaTemplate,
                                   ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    record Envelope(String topic, String key, JsonNode body) {}

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String key, UUID aggregateId, Object event) {
        try {
            String envelope = objectMapper.writeValueAsString(new Envelope(topic, key, objectMapper.valueToTree(event)));
            outbox.save(OutboxMessage.publishEvent(aggregateId, envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento para " + topic, e);
        }
    }

    @Override
    public String messageType() {
        return OutboxMessage.TYPE_PUBLISH_EVENT;
    }

    @Override
    public void handle(OutboxMessage message) {
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), Envelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Mensaje de outbox ilegible " + message.getOutboxId(), e);
        }
        try {
            kafkaTemplate.send(envelope.topic(), envelope.key(), envelope.body()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicación interrumpida en " + envelope.topic(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka no tomó el evento de " + envelope.topic() + ": " + e.getMessage(), e);
        }
    }
}
