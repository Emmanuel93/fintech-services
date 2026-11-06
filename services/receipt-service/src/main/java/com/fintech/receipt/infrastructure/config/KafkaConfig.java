package com.fintech.receipt.infrastructure.config;

import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.infrastructure.adapter.in.messaging.CutoffClosedMessage;
import com.fintech.shared.trace.kafka.DeadLetters;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

@Configuration
public class KafkaConfig {

    private final DeadLetters deadLetters;

    public KafkaConfig(DeadLetters deadLetters) {
        this.deadLetters = deadLetters;
    }

    /**
     * The cutoff's messages, a few lines per poll: each line renders one PDF per client, so a large poll would hold
     * the partition past the poll interval. A message that keeps failing goes to its dead-letter topic.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CutoffClosedMessage>
    cutoffClosedListenerContainerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, CutoffClosedMessage.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CutoffClosedMessage>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        ExponentialBackOff wait = new ExponentialBackOff(1_000L, 2.0);
        wait.setMaxInterval(60_000L);
        wait.setMaxElapsedTime(15 * 60_000L);
        factory.setCommonErrorHandler(deadLetters.errorHandler(wait));
        return factory;
    }

    @Bean
    public NewTopic cutoffClosedTopic(ReceiptProperties properties) {
        return TopicBuilder.name("closing.cutoff-closed").partitions(properties.getPartitions()).build();
    }

    @Bean
    public NewTopic receiptGeneratedTopic(ReceiptProperties properties) {
        return TopicBuilder.name("receipt.receipt-generated").partitions(properties.getPartitions()).build();
    }
}
