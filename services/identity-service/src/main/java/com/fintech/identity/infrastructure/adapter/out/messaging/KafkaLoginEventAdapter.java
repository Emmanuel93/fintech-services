package com.fintech.identity.infrastructure.adapter.out.messaging;

import com.fintech.identity.application.event.LoginAttemptEvent;
import com.fintech.identity.application.port.out.LoginEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaLoginEventAdapter implements LoginEventPublisher {

    static final String TOPIC = "identity.login-attempted";

    private static final Logger log = LoggerFactory.getLogger(KafkaLoginEventAdapter.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaLoginEventAdapter(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(LoginAttemptEvent event) {
        kafkaTemplate.send(TOPIC, event.eventId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Error publicando LoginAttemptEvent eventId={} outcome={}: {}",
                                event.eventId(), event.outcome(), ex.getMessage());
                    } else {
                        log.debug("LoginAttemptEvent publicado eventId={} outcome={} partition={} offset={}",
                                event.eventId(), event.outcome(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
