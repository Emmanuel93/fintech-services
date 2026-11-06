package com.fintech.banking.infrastructure.config;

import com.fintech.banking.infrastructure.adapter.in.messaging.TreasuryCashListener;

import com.fintech.banking.infrastructure.adapter.in.messaging.PayoutTraceListener;
import com.fintech.banking.infrastructure.adapter.in.messaging.StpOrderSettledListener;
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
    public ConcurrentKafkaListenerContainerFactory<String, StpOrderSettledListener.StpOrderSettledPayload>
    stpOrderSettledListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String,
                StpOrderSettledListener.StpOrderSettledPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties,
                StpOrderSettledListener.StpOrderSettledPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PayoutTraceListener.PaymentRequestedPayload>
    paymentRequestedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String,
                PayoutTraceListener.PaymentRequestedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties,
                PayoutTraceListener.PaymentRequestedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PayoutTraceListener.DisbursementAcceptedPayload>
    disbursementAcceptedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String,
                PayoutTraceListener.DisbursementAcceptedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties,
                PayoutTraceListener.DisbursementAcceptedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TreasuryCashListener.CreditReceivedPayload>
    creditReceivedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, TreasuryCashListener.CreditReceivedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, TreasuryCashListener.CreditReceivedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TreasuryCashListener.PaymentAppliedPayload>
    paymentAppliedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, TreasuryCashListener.PaymentAppliedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, TreasuryCashListener.PaymentAppliedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TreasuryCashListener.PaymentReturnedPayload>
    paymentReturnedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, TreasuryCashListener.PaymentReturnedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, TreasuryCashListener.PaymentReturnedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TreasuryCashListener.PaymentRejectedPayload>
    paymentRejectedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, TreasuryCashListener.PaymentRejectedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, TreasuryCashListener.PaymentRejectedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PayoutTraceListener.DisbursementFailedPayload>
    disbursementFailedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PayoutTraceListener.DisbursementFailedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PayoutTraceListener.DisbursementFailedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PayoutTraceListener.DisbursementReturnedPayload>
    disbursementReturnedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PayoutTraceListener.DisbursementReturnedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PayoutTraceListener.DisbursementReturnedPayload.class));
        return factory;
    }
}
