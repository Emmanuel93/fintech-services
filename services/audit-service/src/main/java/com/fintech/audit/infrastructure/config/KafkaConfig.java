package com.fintech.audit.infrastructure.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, String> auditConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * The failure registry's consumer. It sets its own error handler on purpose: a dead letter that cannot be
     * recorded (the database is down) is retried until it can, and never sent to a dead letter of its own (the
     * pattern would pick that one up too). Topic metadata is refreshed every 30 s so a new dead letter topic is
     * subscribed quickly.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> deadLetterListenerContainerFactory(
            KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 30_000);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> auditListenerContainerFactory(
            KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(auditConsumerFactory(properties));
        return factory;
    }
}
