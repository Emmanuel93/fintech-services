package com.fintech.accounting.infrastructure.config;

import com.fintech.accounting.infrastructure.adapter.in.messaging.BalanceUpdatedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CommissionAccruedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CommissionLiquidatedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CommissionReversedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.RecoveryPaymentAppliedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.RiskAssessmentUpdatedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.WithdrawalCompletedPayload;
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
        var factory = new ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, BalanceUpdatedPayload.class));
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
    public ConcurrentKafkaListenerContainerFactory<String, RiskAssessmentUpdatedPayload>
    riskAssessmentUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, RiskAssessmentUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, RiskAssessmentUpdatedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RecoveryPaymentAppliedPayload>
    recoveryPaymentAppliedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, RecoveryPaymentAppliedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, RecoveryPaymentAppliedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, WithdrawalCompletedPayload>
    withdrawalCompletedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, WithdrawalCompletedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, WithdrawalCompletedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CommissionAccruedPayload>
    commissionAccruedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CommissionAccruedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CommissionAccruedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CommissionReversedPayload>
    commissionReversedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CommissionReversedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CommissionReversedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CommissionLiquidatedPayload>
    commissionLiquidatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CommissionLiquidatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CommissionLiquidatedPayload.class));
        return factory;
    }
}
