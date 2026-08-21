package com.fintech.origination.infrastructure.config;

import com.fintech.origination.infrastructure.adapter.in.messaging.ApplicationStartedPayload;
import com.fintech.origination.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.origination.infrastructure.adapter.in.messaging.ScoringCompletedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

@Configuration
public class KafkaConfig {

    // ── Scoring decision consumer (Phase C) ──────────────────────────────────

    @Bean
    public ConsumerFactory<String, ScoringCompletedPayload> scoringDecisionConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ScoringCompletedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ScoringCompletedPayload> scoringDecisionListenerContainerFactory(
            ConsumerFactory<String, ScoringCompletedPayload> scoringDecisionConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ScoringCompletedPayload>();
        factory.setConsumerFactory(scoringDecisionConsumerFactory);
        return factory;
    }

    // ── ApplicationStarted consumer (channels → origination) ─────────────────

    @Bean
    public ConsumerFactory<String, ApplicationStartedPayload> applicationStartedConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ApplicationStartedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ApplicationStartedPayload> applicationStartedListenerContainerFactory(
            ConsumerFactory<String, ApplicationStartedPayload> applicationStartedConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ApplicationStartedPayload>();
        factory.setConsumerFactory(applicationStartedConsumerFactory);
        return factory;
    }

    // ── CreditAccountActivated consumer (Phase G loop-close) ─────────────────

    @Bean
    public ConsumerFactory<String, CreditAccountActivatedPayload> creditAccountActivatedConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, CreditAccountActivatedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload> creditAccountActivatedListenerContainerFactory(
            ConsumerFactory<String, CreditAccountActivatedPayload> creditAccountActivatedConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>();
        factory.setConsumerFactory(creditAccountActivatedConsumerFactory);
        return factory;
    }
}
