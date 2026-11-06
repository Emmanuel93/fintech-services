package com.fintech.charges.infrastructure.config;

import com.fintech.shared.trace.kafka.DeadLetters;
import org.springframework.beans.factory.annotation.Autowired;
import com.fintech.charges.infrastructure.adapter.in.messaging.AccrualDueMessage;
import com.fintech.charges.infrastructure.adapter.in.messaging.BalanceUpdatedPayload;
import com.fintech.charges.infrastructure.adapter.in.messaging.ChargeRejectedPayload;
import com.fintech.charges.infrastructure.adapter.in.messaging.DelinquencyStatusUpdatedPayload;
import com.fintech.charges.infrastructure.adapter.in.messaging.PortfolioDayClosedPayload;
import com.fintech.charges.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.charges.infrastructure.adapter.in.messaging.CreditAccountCancelledPayload;
import com.fintech.charges.infrastructure.adapter.in.messaging.CutoffRunCompletedPayload;
import com.fintech.charges.infrastructure.adapter.out.messaging.KafkaChargesEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;
import com.fintech.charges.infrastructure.adapter.in.messaging.AccountRestructuredPayload;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /** Retries as each listener needs them; when they run out, the message goes to <topic>.DLT instead of a log line. */
    @Autowired
    private DeadLetters deadLetters;

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
    public ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>
    creditAccountActivatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CreditAccountActivatedPayload.class));
        return factory;
    }

    /** A cancelled credit's charges are reversed for sure: retried for a minute before the dead letters. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreditAccountCancelledPayload>
    creditAccountCancelledListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CreditAccountCancelledPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CreditAccountCancelledPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(5_000L, 12)));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AccountRestructuredPayload>
    accountRestructuredListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String,
                AccountRestructuredPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties,
                AccountRestructuredPayload.class));
        // A rate change must not be skipped: retried for a minute before the default handler gives up.
        factory.setCommonErrorHandler(deadLetters.errorHandler(
                new FixedBackOff(5_000L, 12)));
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
    public ConcurrentKafkaListenerContainerFactory<String, DelinquencyStatusUpdatedPayload>
    delinquencyStatusUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DelinquencyStatusUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DelinquencyStatusUpdatedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ChargeRejectedPayload>
    chargeRejectedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ChargeRejectedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, ChargeRejectedPayload.class));
        return factory;
    }

    /**
     * El cierre de cartera es una compuerta: si anotarlo falla, se reintenta durante un minuto en vez
     * de descartarlo a la décima vez inmediata.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PortfolioDayClosedPayload>
    portfolioDayClosedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PortfolioDayClosedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PortfolioDayClosedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(5_000L, 12)));
        return factory;
    }

    /** El fin del corte es una compuerta, como el cierre de cartera: se reintenta en vez de descartarse. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CutoffRunCompletedPayload>
    cutoffRunCompletedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CutoffRunCompletedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CutoffRunCompletedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(5_000L, 12)));
        return factory;
    }

    /**
     * El tópico de trabajo del cierre: una cuenta por mensaje, llave = cuenta. Sus particiones son el techo
     * de paralelismo entre todos los pods.
     */
    @Bean
    public NewTopic accrualDueTopic(@Value("${fintech.day-close.units.partitions:12}") int partitions) {
        return TopicBuilder.name(KafkaChargesEventPublisher.TOPIC_ACCRUAL_DUE).partitions(partitions).build();
    }

    /** Los devengos tardíos, cuenta por cuenta: llave = cuenta, para que los días de una cuenta lleguen en orden. */
    @Bean
    public NewTopic accountAccruedTopic(@Value("${fintech.day-close.units.partitions:12}") int partitions) {
        return TopicBuilder.name(KafkaChargesEventPublisher.TOPIC_ACCOUNT_ACCRUED).partitions(partitions).build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AccrualDueMessage>
    accrualDueListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, AccrualDueMessage>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, AccrualDueMessage.class));
        // Un error de infraestructura (la base no responde) no debe tirar la partición: se reintenta un par
        // de veces y se suelta. La cuenta sigue PENDING y el monitor la vuelve a mandar.
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(1_000L, 2)));
        return factory;
    }
}
