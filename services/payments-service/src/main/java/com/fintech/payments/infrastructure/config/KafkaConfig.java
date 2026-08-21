package com.fintech.payments.infrastructure.config;

import com.fintech.payments.infrastructure.adapter.in.messaging.BalanceUpdatedPayload;
import com.fintech.payments.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.payments.infrastructure.adapter.in.messaging.PaymentRejectedPayload;
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
    public ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>
    balanceUpdatedListenerContainerFactory(KafkaProperties properties) {
        var f = new ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>();
        f.setConsumerFactory(jsonConsumerFactory(properties, BalanceUpdatedPayload.class));
        return f;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>
    creditAccountActivatedListenerContainerFactory(KafkaProperties properties) {
        var f = new ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>();
        f.setConsumerFactory(jsonConsumerFactory(properties, CreditAccountActivatedPayload.class));
        return f;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentRejectedPayload>
    paymentRejectedListenerContainerFactory(KafkaProperties properties) {
        var f = new ConcurrentKafkaListenerContainerFactory<String, PaymentRejectedPayload>();
        f.setConsumerFactory(jsonConsumerFactory(properties, PaymentRejectedPayload.class));
        return f;
    }
}
