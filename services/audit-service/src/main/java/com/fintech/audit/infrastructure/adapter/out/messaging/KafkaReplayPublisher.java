package com.fintech.audit.infrastructure.adapter.out.messaging;

import com.fintech.audit.application.service.failure.ReplayPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Its own producer, with text serializers: the payload goes back exactly as the dead letter kept it. Waits for the
 * broker's acknowledgement, so a replay reported as done was really written to the topic.
 */
@Component
class KafkaReplayPublisher implements ReplayPublisher {

    private final KafkaTemplate<String, String> template;

    KafkaReplayPublisher(KafkaProperties properties) {
        Map<String, Object> props = new HashMap<>(properties.buildProducerProperties(null));
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "audit-service-replay");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        this.template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Override
    public void republish(String topic, String key, String payload, Map<String, byte[]> headers) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        headers.forEach((name, value) -> record.headers().add(name, value));
        try {
            template.send(record).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió el reproceso hacia " + topic, e);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo reenviar el mensaje a " + topic + ": " + e.getMessage(), e);
        }
    }
}
