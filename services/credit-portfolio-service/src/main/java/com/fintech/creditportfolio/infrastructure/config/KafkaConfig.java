package com.fintech.creditportfolio.infrastructure.config;

import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.ChargeAppliedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.ChargeReversedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.CollectionAgreementExecutedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.CreditProductCreationRequestedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.DisbursementCompletedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.DisbursementFailedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.DispositionRequestedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.PaymentAppliedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.PaymentReturnedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.ProductActivatedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.ProductRetiredPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.WriteOffExecutedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.PortfolioAssignedPayload;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

@Configuration
public class KafkaConfig {

    // El payload del reparto vive en el paquete de mensajería de entrada.

    /** Shared consumer config builder — JSON value deserializer pinned to a default type. */
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

    // ── origination.credit-product-creation-requested ────────────────────────

    @Bean
    public ConsumerFactory<String, CreditProductCreationRequestedPayload> creditProductCreationConsumerFactory(
            KafkaProperties properties) {
        return jsonConsumerFactory(properties, CreditProductCreationRequestedPayload.class);
    }

    /** El reparto de cartera que decide sales-org al desembolsar: de aquí sale la sucursal. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PortfolioAssignedPayload>
    portfolioAssignedListenerContainerFactory(KafkaProperties properties) {
        java.util.Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.springframework.kafka.support.serializer.JsonDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.VALUE_DEFAULT_TYPE,
                PortfolioAssignedPayload.class.getName());
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, PortfolioAssignedPayload>();
        factory.setConsumerFactory(
                new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(config));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreditProductCreationRequestedPayload>
    creditProductCreationListenerContainerFactory(
            ConsumerFactory<String, CreditProductCreationRequestedPayload> creditProductCreationConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<
                String, CreditProductCreationRequestedPayload>();
        factory.setConsumerFactory(creditProductCreationConsumerFactory);
        return factory;
    }

    // ── product-catalog.product-activated ────────────────────────────────────

    @Bean
    public ConsumerFactory<String, ProductActivatedPayload> productActivatedConsumerFactory(
            KafkaProperties properties) {
        return jsonConsumerFactory(properties, ProductActivatedPayload.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProductActivatedPayload>
    productActivatedListenerContainerFactory(
            ConsumerFactory<String, ProductActivatedPayload> productActivatedConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ProductActivatedPayload>();
        factory.setConsumerFactory(productActivatedConsumerFactory);
        return factory;
    }

    // ── product-catalog.product-retired ──────────────────────────────────────

    @Bean
    public ConsumerFactory<String, ProductRetiredPayload> productRetiredConsumerFactory(
            KafkaProperties properties) {
        return jsonConsumerFactory(properties, ProductRetiredPayload.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProductRetiredPayload>
    productRetiredListenerContainerFactory(
            ConsumerFactory<String, ProductRetiredPayload> productRetiredConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ProductRetiredPayload>();
        factory.setConsumerFactory(productRetiredConsumerFactory);
        return factory;
    }

    // ── Balance engine inbound (charges / payments / collections) ────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ChargeAppliedPayload>
    chargeAppliedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ChargeAppliedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, ChargeAppliedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ChargeReversedPayload>
    chargeReversedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ChargeReversedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, ChargeReversedPayload.class));
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
    public ConcurrentKafkaListenerContainerFactory<String, PaymentReturnedPayload>
    paymentReturnedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PaymentReturnedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PaymentReturnedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, WriteOffExecutedPayload>
    writeOffListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, WriteOffExecutedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, WriteOffExecutedPayload.class));
        return factory;
    }

    // ── wallet.disposition-requested ─────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DispositionRequestedPayload>
    dispositionRequestedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DispositionRequestedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DispositionRequestedPayload.class));
        return factory;
    }

    // ── collections.agreement-executed ───────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CollectionAgreementExecutedPayload>
    collectionAgreementExecutedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CollectionAgreementExecutedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CollectionAgreementExecutedPayload.class));
        return factory;
    }

    // ── disbursement.completed / disbursement.failed (cierre del ciclo) ──────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DisbursementCompletedPayload>
    disbursementCompletedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DisbursementCompletedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DisbursementCompletedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DisbursementFailedPayload>
    disbursementFailedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DisbursementFailedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DisbursementFailedPayload.class));
        return factory;
    }
}
