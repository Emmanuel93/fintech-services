package com.fintech.creditportfolio.infrastructure.config;

import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.PlacementPolicyChangedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.BusinessCalendarDayListener;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.CollectionAccountAssignedListener;
import com.fintech.shared.trace.kafka.DeadLetters;
import org.springframework.beans.factory.annotation.Autowired;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.ChargeAppliedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.ChargeReversedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.CollectionAgreementExecutedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.CreditProductCreationRequestedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.DisbursementCompletedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.DisbursementFailedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.CutoffClosedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.DayAccruedPayload;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.DisbursementReturnedPayload;
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
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.Map;

@Configuration
public class KafkaConfig {

    /** Retries as each listener needs them; when they run out, the message goes to <topic>.DLT instead of a log line. */
    @Autowired
    private DeadLetters deadLetters;

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

    // ── sales-org.placement-policy-changed ──────────────────────────────────────

    /** The branch's placement policy, resolved per unit by sales-org: the portfolio's copy for authorizing placements. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PlacementPolicyChangedPayload>
    placementPolicyChangedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PlacementPolicyChangedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PlacementPolicyChangedPayload.class));
        return factory;
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

    // ── compliance.verification-completed ────────────────────────────────────
    //
    // El veredicto de KYC/AML de cada persona. Se proyecta para poder frenar una disposición sin
    // salir a preguntar: la comprobación está en el camino del dinero.

    @org.springframework.context.annotation.Bean
    public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String,
            com.fintech.creditportfolio.infrastructure.adapter.in.messaging.VerificationCompletedPayload>
    verificationCompletedListenerContainerFactory(
            org.springframework.boot.autoconfigure.kafka.KafkaProperties properties) {
        java.util.Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.springframework.kafka.support.serializer.JsonDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.VALUE_DEFAULT_TYPE,
                com.fintech.creditportfolio.infrastructure.adapter.in.messaging.VerificationCompletedPayload.class.getName());
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        var factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String,
                com.fintech.creditportfolio.infrastructure.adapter.in.messaging.VerificationCompletedPayload>();
        factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(config));
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

    // ── closing.business-calendar-day ────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CollectionAccountAssignedListener.CollectionAccountAssignedPayload>
    collectionAccountAssignedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CollectionAccountAssignedListener.CollectionAccountAssignedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CollectionAccountAssignedListener.CollectionAccountAssignedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BusinessCalendarDayListener.CalendarDayPayload>
    businessCalendarDayListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, BusinessCalendarDayListener.CalendarDayPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, BusinessCalendarDayListener.CalendarDayPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    // ── Balance engine inbound (charges / payments / collections) ────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ChargeAppliedPayload>
    chargeAppliedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ChargeAppliedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, ChargeAppliedPayload.class));
        // Un cargo descartado es interés que falta: se reintenta medio minuto, no diez veces seguidas en
        // un instante. Si aun así se pierde, las cifras de control del día lo detectan.
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(3_000L, 10)));
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

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CutoffClosedPayload>
    cutoffClosedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CutoffClosedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CutoffClosedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DisbursementReturnedPayload>
    disbursementReturnedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DisbursementReturnedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DisbursementReturnedPayload.class));
        return factory;
    }

    /**
     * {@code charges.day-accrued}: un mensaje al día que dispara minutos de trabajo (la mora de toda la
     * cartera). Uno a la vez y con margen de sondeo amplio para que el broker no lo dé por muerto a
     * medio cierre; si falla, dos reintentos espaciados y después lo retoma el respaldo de las 02:00.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DayAccruedPayload>
    dayAccruedListenerContainerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, (int) Duration.ofHours(2).toMillis());
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                DayAccruedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DayAccruedPayload>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(60_000L, 2)));
        return factory;
    }

    /**
     * Las cuentas que devengaron tarde. Pocas y con paciencia, como el aviso del día: el listener puede esperar
     * a que cartera consuma los cargos de esa cuenta. Si no se suelta, queda anotada y la retoma la reanudación.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, com.fintech.creditportfolio.infrastructure.adapter.in.messaging.AccountAccruedPayload>
    accountAccruedListenerContainerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, (int) Duration.ofHours(2).toMillis());
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                com.fintech.creditportfolio.infrastructure.adapter.in.messaging.AccountAccruedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        var factory = new ConcurrentKafkaListenerContainerFactory<String,
                com.fintech.creditportfolio.infrastructure.adapter.in.messaging.AccountAccruedPayload>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(5_000L, 6)));
        return factory;
    }
}
