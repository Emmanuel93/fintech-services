package com.fintech.notifications.infrastructure.config;

import com.fintech.notifications.infrastructure.adapter.in.messaging.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import com.fintech.notifications.infrastructure.adapter.in.messaging.NotificationRequestedPayload;
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
    public ConcurrentKafkaListenerContainerFactory<String, ProspectCreatedPayload>
    prospectCreatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ProspectCreatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, ProspectCreatedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, InstallmentDuePayload>
    installmentDueListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, InstallmentDuePayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, InstallmentDuePayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OfferPresentedPayload>
    offerPresentedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, OfferPresentedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, OfferPresentedPayload.class));
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
    public ConcurrentKafkaListenerContainerFactory<String, DispositionCompletedPayload>
    dispositionCompletedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DispositionCompletedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DispositionCompletedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PreDueReminderTriggeredPayload>
    preDueReminderTriggeredListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PreDueReminderTriggeredPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PreDueReminderTriggeredPayload.class));
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
    public ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>
    balanceUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, BalanceUpdatedPayload.class));
        return factory;
    }

    // ── Cobranza ─────────────────────────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DunningRequestedPayload>
    dunningRequestedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DunningRequestedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DunningRequestedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentThanksListener.PaymentThanksPayload>
    paymentThanksListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PaymentThanksListener.PaymentThanksPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PaymentThanksListener.PaymentThanksPayload.class));
        return factory;
    }

    /** El carril genérico: un solo tópico para todos los avisos internos. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationRequestedPayload>
    notificationRequestedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, NotificationRequestedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, NotificationRequestedPayload.class));
        return factory;
    }
}
