package com.fintech.closing.infrastructure.config;

import com.fintech.shared.trace.kafka.DeadLetters;
import org.springframework.beans.factory.annotation.Autowired;
import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.infrastructure.adapter.in.messaging.CutoffDueMessage;
import com.fintech.closing.infrastructure.adapter.in.messaging.DayClosedMessage;
import com.fintech.closing.infrastructure.adapter.out.messaging.KafkaClosingEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.util.backoff.FixedBackOff;

import com.fintech.closing.infrastructure.adapter.in.messaging.BalanceUpdatedPayload;
import com.fintech.closing.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.closing.infrastructure.adapter.in.messaging.DelinquencyStatusUpdatedPayload;
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
 * Una fábrica por payload, y no una compartida.
 *
 * <p>{@code VALUE_DEFAULT_TYPE} fija el tipo a deserializar <b>por fábrica</b>, así que una sola
 * para tres tópicos deserializaría los tres como el mismo récord. Es la convención del monorepo y
 * la razón por la que estas tres beans se parecen tanto.
 */
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
    closingAccountActivatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CreditAccountActivatedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>
    closingBalanceUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, BalanceUpdatedPayload.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CutoffDueMessage>
    cutoffDueListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CutoffDueMessage>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CutoffDueMessage.class));
        // Un error de infraestructura (la base no responde) no debe tirar la partición: se reintenta
        // un par de veces y se suelta. La unidad sigue PENDING y el monitor la vuelve a mandar.
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(1_000L, 2)));
        return factory;
    }

    /** El aviso de cartera de que cerró el día: un mensaje diario que planifica el corte. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DayClosedMessage>
    dayClosedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DayClosedMessage>();
        var consumidor = jsonConsumerFactory(properties, DayClosedMessage.class);
        // Uno a la vez y con paciencia: el listener puede esperar minutos a que closing consuma los
        // saldos del día antes de cortar, y el broker no debe darlo por muerto mientras.
        var config = new java.util.HashMap<>(consumidor.getConfigurationProperties());
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                (int) java.time.Duration.ofHours(2).toMillis());
        factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(config));
        // Si la base no responde se reintenta; si sigue sin responder, lo retoma la reanudación.
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(5_000L, 3)));
        return factory;
    }

    /**
     * Los tópicos del corte se declaran con particiones: son el techo de paralelismo. Si ya existían
     * con menos —el broker los crea con una—, {@code KafkaAdmin} les agrega las que falten.
     */
    @Bean
    public NewTopic cutoffDueTopic(ClosingProperties properties) {
        return TopicBuilder.name(KafkaClosingEventPublisher.TOPIC_CUTOFF_DUE)
                .partitions(properties.getCutoff().getPartitions()).build();
    }

    @Bean
    public NewTopic cutoffClosedTopic(ClosingProperties properties) {
        return TopicBuilder.name(KafkaClosingEventPublisher.TOPIC_CUTOFF_CLOSED)
                .partitions(properties.getCutoff().getPartitions()).build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DelinquencyStatusUpdatedPayload>
    closingDelinquencyListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DelinquencyStatusUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DelinquencyStatusUpdatedPayload.class));
        return factory;
    }

    /**
     * Las cuentas retenidas que cartera suelta. La liberación espera en línea a que closing consuma los saldos;
     * si no alcanza, se reintenta en vez de descartarse.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, com.fintech.closing.infrastructure.adapter.in.messaging.AccountDayClosedMessage>
    accountDayClosedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String,
                com.fintech.closing.infrastructure.adapter.in.messaging.AccountDayClosedMessage>();
        var consumidor = jsonConsumerFactory(properties,
                com.fintech.closing.infrastructure.adapter.in.messaging.AccountDayClosedMessage.class);
        var config = new java.util.HashMap<>(consumidor.getConfigurationProperties());
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                (int) java.time.Duration.ofHours(2).toMillis());
        factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(deadLetters.errorHandler(new FixedBackOff(5_000L, 12)));
        return factory;
    }
}
