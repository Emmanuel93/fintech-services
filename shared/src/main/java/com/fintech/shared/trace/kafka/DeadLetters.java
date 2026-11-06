package com.fintech.shared.trace.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What happens to a message a listener cannot handle: it is retried with a growing wait and, when the retries run
 * out, it goes to {@code <topic>.DLT} with the original key, value and headers (its correlation id among them),
 * where it failed (topic, partition, offset, consumer group, service) and why (the exception). The failure
 * registry consumes every {@code .DLT} topic: a failure can be found by its correlation id and replayed.
 *
 * <p>Before this, seventeen services ran Spring Kafka's default handling: ten attempts with no wait, then one log
 * line and the message was gone.
 *
 * <p>The dead letter is written with its own producer, which writes text as it came and anything else as JSON:
 * the service's template may serialize values its own way, and a dead letter has to be replayable as it was read.
 */
public class DeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);

    public static final String DLT_SUFFIX = ".DLT";
    public static final String HEADER_SERVICE = "x-failed-service";

    private final KafkaProperties kafkaProperties;
    private final String serviceName;
    private final int retries;
    private final long initialIntervalMs;
    private volatile KafkaTemplate<Object, Object> template;

    public DeadLetters(KafkaProperties kafkaProperties, String serviceName) {
        this(kafkaProperties, serviceName, 5, 1_000L);
    }

    /**
     * @param retries           attempts after the first one ({@code fintech.kafka.failures.retries}, 5 by default)
     * @param initialIntervalMs the first wait, doubled on each retry up to 30 s
     *                          ({@code fintech.kafka.failures.initial-interval-ms}, 1 s by default)
     */
    public DeadLetters(KafkaProperties kafkaProperties, String serviceName, int retries, long initialIntervalMs) {
        this.kafkaProperties = kafkaProperties;
        this.serviceName = serviceName;
        this.retries = retries;
        this.initialIntervalMs = initialIntervalMs;
    }

    /** By default retries after 1 s, 2 s, 4 s, 8 s and 16 s; then the dead letter. */
    public CommonErrorHandler errorHandler() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retries);
        backOff.setInitialInterval(initialIntervalMs);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);
        return errorHandler(backOff);
    }

    /** The retries a listener needs (some wait for another service to catch up), then the dead letter. */
    public CommonErrorHandler errorHandler(BackOff backOff) {
        return new DefaultErrorHandler(recoverer(), backOff);
    }

    public ConsumerRecordRecoverer recoverer() {
        return (record, exception) -> {
            DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template(),
                    (r, e) -> new TopicPartition(r.topic() + DLT_SUFFIX, -1));
            dlt.setHeadersFunction((r, e) -> {
                RecordHeaders h = new RecordHeaders();
                h.add(HEADER_SERVICE, serviceName.getBytes(StandardCharsets.UTF_8));
                return h;
            });
            log.error("Mensaje sin procesar tras los reintentos: va a {}{} topic={} partition={} offset={} correlationId={} causa={}",
                    record.topic(), DLT_SUFFIX, record.topic(), record.partition(), record.offset(),
                    CorrelationRecordInterceptor.of(record.headers()), exception.toString());
            dlt.accept(record, exception);
        };
    }

    private KafkaTemplate<Object, Object> template() {
        KafkaTemplate<Object, Object> t = template;
        if (t == null) {
            synchronized (this) {
                if (template == null) {
                    Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
                    props.put(ProducerConfig.CLIENT_ID_CONFIG, serviceName + "-dead-letters");
                    template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props, new AsIs(), new AsIs()));
                }
                t = template;
            }
        }
        return t;
    }

    /** Text as it came, bytes as they came, anything else as JSON. */
    static final class AsIs implements Serializer<Object> {
        private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

        @Override
        public byte[] serialize(String topic, Object data) {
            if (data == null) return null;
            if (data instanceof byte[] b) return b;
            if (data instanceof String s) return s.getBytes(StandardCharsets.UTF_8);
            try {
                return JSON.writeValueAsBytes(data);
            } catch (Exception e) {
                return String.valueOf(data).getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    static String describe(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }
}
