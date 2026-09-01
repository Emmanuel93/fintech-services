package com.fintech.risk.infrastructure.config;

import com.fintech.risk.infrastructure.adapter.in.messaging.BalanceUpdatedPayload;
import com.fintech.risk.infrastructure.adapter.in.messaging.CollectionAgreementExecutedPayload;
import com.fintech.risk.infrastructure.adapter.in.messaging.ReliefGrantedPayload;
import com.fintech.risk.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.risk.infrastructure.adapter.in.messaging.DelinquencyStatusUpdatedPayload;
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

    private <T> ConsumerFactory<String, T> jsonConsumerFactory(KafkaProperties properties, Class<T> type) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, type.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>
    creditAccountActivatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CreditAccountActivatedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>
    balanceUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, BalanceUpdatedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DelinquencyStatusUpdatedPayload>
    delinquencyStatusUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DelinquencyStatusUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DelinquencyStatusUpdatedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CollectionAgreementExecutedPayload>
    collectionAgreementExecutedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CollectionAgreementExecutedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CollectionAgreementExecutedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ReliefGrantedPayload>
    reliefGrantedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ReliefGrantedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, ReliefGrantedPayload.class));
        return factory;
    }
}
