package com.fintech.beneficiary.infrastructure.config;

import com.fintech.beneficiary.infrastructure.adapter.in.messaging.DispositionCompletedListener.DispositionCompletedPayload;
import com.fintech.beneficiary.infrastructure.adapter.in.messaging.DispositionFailedListener.DispositionFailedPayload;
import com.fintech.beneficiary.infrastructure.adapter.in.messaging.DispositionRejectedListener;
import com.fintech.beneficiary.infrastructure.adapter.in.messaging.ProductActivatedPayload;
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

/**
 * Consumo de los eventos de otros dominios.
 *
 * <p>El tipo se fija por fábrica y se apagan las cabeceras de tipo
 * ({@code USE_TYPE_INFO_HEADERS = false}): el productor publica su propia clase, que aquí no
 * existe, y confiar en la cabecera haría fallar la deserialización con un
 * {@code ClassNotFoundException} por cada mensaje.
 */
@Configuration
class KafkaConsumerConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, DispositionCompletedPayload>
    kafkaListenerContainerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DispositionCompletedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, DispositionCompletedPayload>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        return factory;
    }

    /**
     * Factory propio para {@code product-catalog.product-activated} (F3b).
     *
     * <p>Hace falta uno aparte porque el tipo del valor se fija por fábrica con
     * {@code VALUE_DEFAULT_TYPE}: el de arriba está clavado a {@code DispositionCompletedPayload} y
     * un segundo listener con otro payload deserializaría contra la clase equivocada. Por eso el
     * listener lo nombra explícitamente en {@code containerFactory}.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ProductActivatedPayload>
    productActivatedListenerContainerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ProductActivatedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, ProductActivatedPayload>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        return factory;
    }

    /**
     * Its own factory for {@code credit-portfolio.disposition-failed} (D63), for the same reason as the one above.
     * Without it, the listener read every message as a {@code DispositionCompletedPayload}, and each one went to the
     * DLT: the placement whose SPEI failed stayed «depositando».
     */
    /** credit-portfolio.disposition-rejected: a placement the portfolio refused, with its reason. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, DispositionRejectedListener.DispositionRejectedPayload>
    dispositionRejectedListenerContainerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DispositionRejectedListener.DispositionRejectedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DispositionRejectedListener.DispositionRejectedPayload>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, DispositionFailedPayload>
    dispositionFailedListenerContainerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DispositionFailedPayload.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, DispositionFailedPayload>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        return factory;
    }
}
