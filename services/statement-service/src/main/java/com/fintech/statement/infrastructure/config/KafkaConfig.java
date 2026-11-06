package com.fintech.statement.infrastructure.config;

import com.fintech.shared.trace.kafka.DeadLetters;
import org.springframework.beans.factory.annotation.Autowired;
import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.infrastructure.adapter.in.messaging.CutoffClosedMessage;
import com.fintech.statement.infrastructure.adapter.in.messaging.CutoffRunCompletedMessage;
import com.fintech.statement.infrastructure.adapter.in.messaging.CutoffRunPlannedMessage;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * Una fábrica por payload, como en el resto del monorepo: {@code VALUE_DEFAULT_TYPE} es por fábrica.
 */
@Configuration
public class KafkaConfig {

    /** Retries as each listener needs them; when they run out, the message goes to <topic>.DLT instead of a log line. */
    @Autowired
    private DeadLetters deadLetters;

    private <T> ConsumerFactory<String, T> jsonConsumerFactory(KafkaProperties properties, Class<T> type,
                                                               Integer maxPollRecords) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, type.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        if (maxPollRecords != null) {
            config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        }
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * El consumidor del trabajo. Cada mensaje es un PDF —decenas de milisegundos—, así que se piden
     * pocos por vuelta: con 500 un rebalanceo esperaría a que el hilo termine un lote de minutos.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CutoffClosedMessage>
    cutoffClosedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CutoffClosedMessage>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CutoffClosedMessage.class, 20));
        // La generación guarda su propio fallo (cartera caída, plantilla rota): eso nunca llega aquí.
        // Lo que llega es infraestructura —la base no responde al registrar—, y soltar el mensaje
        // perdería el estado de cuenta: nadie más sabe que ese corte existió. Se espera con backoff
        // hasta 15 minutos; mientras, la partición se detiene, que es lo correcto si la base cayó.
        ExponentialBackOff espera = new ExponentialBackOff(1_000L, 2.0);
        espera.setMaxInterval(60_000L);
        espera.setMaxElapsedTime(15 * 60_000L);
        factory.setCommonErrorHandler(deadLetters.errorHandler(espera));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CutoffRunPlannedMessage>
    cutoffRunPlannedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CutoffRunPlannedMessage>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CutoffRunPlannedMessage.class, null));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CutoffRunCompletedMessage>
    cutoffRunCompletedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CutoffRunCompletedMessage>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CutoffRunCompletedMessage.class, null));
        return factory;
    }

    /** Si este servicio arranca antes que el cierre, el tópico de trabajo nace con sus particiones. */
    @Bean
    public NewTopic cutoffClosedTopic(StatementProperties properties) {
        return TopicBuilder.name("closing.cutoff-closed").partitions(properties.getPartitions()).build();
    }

    @Bean
    public NewTopic statementGeneratedTopic(StatementProperties properties) {
        return TopicBuilder.name("statement.statement-generated").partitions(properties.getPartitions()).build();
    }
}
