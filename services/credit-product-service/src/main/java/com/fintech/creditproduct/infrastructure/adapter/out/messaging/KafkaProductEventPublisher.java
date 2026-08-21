package com.fintech.creditproduct.infrastructure.adapter.out.messaging;

import com.fintech.creditproduct.application.port.out.ProductEventPublisher;
import com.fintech.creditproduct.domain.event.ProductActivatedEvent;
import com.fintech.creditproduct.domain.event.ProductRetiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaProductEventPublisher implements ProductEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaProductEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String activatedTopic;
    private final String retiredTopic;

    public KafkaProductEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${kafka.topics.product-activated:product-catalog.product-activated}") String activatedTopic,
            @Value("${kafka.topics.product-retired:product-catalog.product-retired}") String retiredTopic) {
        this.kafkaTemplate  = kafkaTemplate;
        this.activatedTopic = activatedTopic;
        this.retiredTopic   = retiredTopic;
    }

    @Override
    public void publishProductActivated(ProductActivatedEvent event) {
        kafkaTemplate.send(activatedTopic, event.productCode(), event);
        log.info("ProductActivated published: code={} version={}", event.productCode(), event.productVersion());
    }

    @Override
    public void publishProductRetired(ProductRetiredEvent event) {
        kafkaTemplate.send(retiredTopic, event.productCode(), event);
        log.info("ProductRetired published: code={} retiredVersion={}", event.productCode(), event.retiredVersion());
    }
}
