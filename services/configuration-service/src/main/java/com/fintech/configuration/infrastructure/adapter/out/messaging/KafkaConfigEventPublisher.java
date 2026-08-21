package com.fintech.configuration.infrastructure.adapter.out.messaging;

import com.fintech.configuration.application.port.out.ConfigEventPublisher;
import com.fintech.configuration.domain.ConfigParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

@Component
public class KafkaConfigEventPublisher implements ConfigEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfigEventPublisher.class);
    private static final String TOPIC = "configuration.configuration-updated";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaConfigEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishConfigurationUpdated(ConfigParameter parameter) {
        try {
            Map<String, Object> payload = Map.of(
                    "key", parameter.getParamKey(),
                    "newValue", parameter.getValue(),
                    "effectiveDate", parameter.getEffectiveDate() != null
                            ? parameter.getEffectiveDate().toString()
                            : LocalDate.now().toString(),
                    "productType", parameter.getProductType() != null ? parameter.getProductType() : "",
                    "channelType", parameter.getChannelType() != null ? parameter.getChannelType() : ""
            );
            kafkaTemplate.send(TOPIC, parameter.getParamKey(), payload);
        } catch (Exception e) {
            // Kafka failure must not roll back the approval — log and continue
            log.error("Failed to publish ConfigurationUpdated for key={}", parameter.getParamKey(), e);
        }
    }
}
