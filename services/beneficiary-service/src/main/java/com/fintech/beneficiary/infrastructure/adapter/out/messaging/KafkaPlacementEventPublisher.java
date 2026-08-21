package com.fintech.beneficiary.infrastructure.adapter.out.messaging;

import com.fintech.beneficiary.application.port.out.PlacementEventPublisher;
import com.fintech.beneficiary.domain.event.PlacementEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica cada evento en el tópico que el propio evento declara, particionado por
 * {@code placementId}.
 *
 * <p>La clave es el {@code placementId} y no el del distribuidor para que el orden se preserve
 * <b>por colocación</b>: lo que no puede reordenarse es el {@code kyc-completed} antes que el
 * {@code kyc-started} de la misma persona. Dos colocaciones del mismo distribuidor son
 * independientes y no ganan nada compartiendo partición — al contrario, la comparten peor.
 */
@Component
public class KafkaPlacementEventPublisher implements PlacementEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPlacementEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(PlacementEvent event) {
        kafkaTemplate.send(event.topic(), event.getPlacementId().toString(), event);
    }
}
