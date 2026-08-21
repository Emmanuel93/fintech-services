package com.fintech.stp.infrastructure.config;

import com.fintech.stp.infrastructure.adapter.in.messaging.StpPaymentRequestedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

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
    public ConcurrentKafkaListenerContainerFactory<String, StpPaymentRequestedPayload>
    stpPaymentRequestedListenerContainerFactory(KafkaProperties properties,
                                                 DefaultErrorHandler stpErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, StpPaymentRequestedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, StpPaymentRequestedPayload.class));
        factory.setCommonErrorHandler(stpErrorHandler);
        return factory;
    }

    /**
     * Backoff exponencial no bloqueante y DLT. El monorepo no tenía ninguno; estos dos servicios son
     * los primeros porque son los que mueven dinero: cada mensaje en el DLT es un desembolso que no
     * salió y necesita a alguien mirándolo.
     *
     * <p>Las excepciones deterministas van directo al DLT sin gastar reintentos: reintentar una
     * empresa inexistente o una CLABE inválida sólo retrasa el diagnóstico.
     */
    @Bean
    public DefaultErrorHandler stpErrorHandler(KafkaTemplate<String, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));

        var backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(15_000L);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                com.fintech.stp.domain.CompanyNotFoundException.class,
                com.fintech.stp.domain.OrderingAccountNotFoundException.class,
                com.fintech.stp.domain.InvalidBeneficiaryAccountException.class,
                com.fintech.stp.domain.SigningKeyNotAvailableException.class);
        return handler;
    }
}
