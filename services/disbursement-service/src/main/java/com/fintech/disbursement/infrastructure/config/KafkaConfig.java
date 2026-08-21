package com.fintech.disbursement.infrastructure.config;

import com.fintech.disbursement.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.DispositionAuthorizedPayload;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.ProviderOutcomePayloads;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.WalletWithdrawalCompletedPayload;
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
        // El emisor manda su propio tipo en las cabeceras; aquí manda el contrato local. Es lo que
        // hace que un cambio de paquete del otro lado no rompa este consumidor.
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> factory(
            KafkaProperties properties, Class<T> type, DefaultErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, T>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, type));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    // ── ACL: los tres orígenes ────────────────────────────────────────────────
    // Borrar estos tres beans junto con sus listeners deja el servicio funcionando por REST.

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>
    creditAccountActivatedListenerContainerFactory(KafkaProperties p, DefaultErrorHandler h) {
        return factory(p, CreditAccountActivatedPayload.class, h);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DispositionAuthorizedPayload>
    dispositionAuthorizedListenerContainerFactory(KafkaProperties p, DefaultErrorHandler h) {
        return factory(p, DispositionAuthorizedPayload.class, h);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, WalletWithdrawalCompletedPayload>
    walletWithdrawalListenerContainerFactory(KafkaProperties p, DefaultErrorHandler h) {
        return factory(p, WalletWithdrawalCompletedPayload.class, h);
    }

    // ── Resultados de los conectores ──────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProviderOutcomePayloads.Accepted>
    providerAcceptedListenerContainerFactory(KafkaProperties p, DefaultErrorHandler h) {
        return factory(p, ProviderOutcomePayloads.Accepted.class, h);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProviderOutcomePayloads.Settled>
    providerSettledListenerContainerFactory(KafkaProperties p, DefaultErrorHandler h) {
        return factory(p, ProviderOutcomePayloads.Settled.class, h);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProviderOutcomePayloads.Rejected>
    providerRejectedListenerContainerFactory(KafkaProperties p, DefaultErrorHandler h) {
        return factory(p, ProviderOutcomePayloads.Rejected.class, h);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProviderOutcomePayloads.Returned>
    providerReturnedListenerContainerFactory(KafkaProperties p, DefaultErrorHandler h) {
        return factory(p, ProviderOutcomePayloads.Returned.class, h);
    }

    /**
     * Backoff exponencial no bloqueante y DLT.
     *
     * <p>Cada mensaje que cae al DLT es un desembolso que no salió y necesita a alguien mirándolo.
     * Los errores deterministas van directo, sin gastar reintentos: reintentar una empresa sin mapeo
     * o una CLABE inválida sólo retrasa el diagnóstico.
     */
    @Bean
    public DefaultErrorHandler disbursementErrorHandler(KafkaTemplate<String, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));

        // Backoff exponencial acotado por tiempo (equivale a ~4 reintentos: 1s,2s,4s,8s) antes de DLT.
        var backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(15_000L);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                com.fintech.disbursement.domain.UnresolvedCompanyException.class,
                com.fintech.disbursement.domain.InvalidBeneficiaryAccountException.class,
                com.fintech.disbursement.domain.DisbursementNotFoundException.class);
        return handler;
    }
}
