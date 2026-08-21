package com.fintech.collections.infrastructure.config;

import com.fintech.collections.infrastructure.adapter.in.messaging.BalanceUpdatedPayload;
import com.fintech.collections.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.collections.infrastructure.adapter.in.messaging.DelinquencyStatusUpdatedPayload;
import com.fintech.collections.infrastructure.adapter.in.messaging.InstallmentUpcomingPayload;
import com.fintech.collections.infrastructure.adapter.in.messaging.NotificationSettledPayload;
import com.fintech.collections.infrastructure.adapter.in.messaging.PaymentAppliedPayload;
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
    public ConcurrentKafkaListenerContainerFactory<String, DelinquencyStatusUpdatedPayload>
    delinquencyStatusUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DelinquencyStatusUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DelinquencyStatusUpdatedPayload.class));
        return factory;
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
    public ConcurrentKafkaListenerContainerFactory<String, PaymentAppliedPayload>
    paymentAppliedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PaymentAppliedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PaymentAppliedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, InstallmentUpcomingPayload>
    installmentUpcomingListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, InstallmentUpcomingPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, InstallmentUpcomingPayload.class));
        return factory;
    }

    // El desenlace de los mensajes que cobranza pidió. Dos tópicos, un mismo tipo: los payloads de
    // notifications se diferencian sólo en failureReason.

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationSettledPayload>
    notificationSentListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, NotificationSettledPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, NotificationSettledPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationSettledPayload>
    notificationFailedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, NotificationSettledPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, NotificationSettledPayload.class));
        return factory;
    }
}
