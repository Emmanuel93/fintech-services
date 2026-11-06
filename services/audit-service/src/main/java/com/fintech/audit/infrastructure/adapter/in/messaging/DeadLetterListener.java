package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.failure.FailedMessageService;
import com.fintech.audit.domain.failure.FailedMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every dead letter topic of the platform ({@code <topic>.DLT}, and the {@code .dlt} that disbursement and STP
 * already had) feeds the failure registry. The subscription is by pattern: a topic that gets its first dead
 * letter is picked up within a minute, without deploying anything.
 */
@Component
class DeadLetterListener {

    static final String PATTERN = ".*\\.(DLT|dlt)";
    private static final String CORRELATION = "x-correlation-id";
    private static final String SERVICE = "x-failed-service";

    private final FailedMessageService failures;

    DeadLetterListener(FailedMessageService failures) {
        this.failures = failures;
    }

    @KafkaListener(topicPattern = PATTERN, groupId = "audit-failed-messages",
                   containerFactory = "deadLetterListenerContainerFactory")
    void onDeadLetter(ConsumerRecord<String, String> record) {
        failures.record(toFailedMessage(record));
    }

    static FailedMessage toFailedMessage(ConsumerRecord<String, String> record) {
        Map<String, String> originales = new LinkedHashMap<>();
        for (Header h : record.headers()) {
            // The dead letter's own headers describe the failure; the rest are the message's and go back on replay.
            if (!h.key().startsWith("kafka_dlt-") && !SERVICE.equals(h.key()) && h.value() != null) {
                originales.put(h.key(), Base64.getEncoder().encodeToString(h.value()));
            }
        }
        String topicoOriginal = text(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (topicoOriginal == null) {
            topicoOriginal = record.topic().replaceFirst("\\.(DLT|dlt)$", "");
        }
        Long ts = number(record, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);
        Long particion = number(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        return new FailedMessage(UUID.randomUUID(), record.topic(), record.partition(), record.offset(),
                topicoOriginal, particion == null ? null : particion.intValue(), number(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                text(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP), text(record, SERVICE), text(record, CORRELATION),
                record.key(), record.value(), originales,
                text(record, KafkaHeaders.DLT_EXCEPTION_FQCN), text(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                ts == null ? Instant.ofEpochMilli(record.timestamp()) : Instant.ofEpochMilli(ts), null,
                FailedMessage.PENDING, 0, null, null, null);
    }

    private static String text(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** Partition (4 bytes), offset and timestamp (8 bytes), as Spring Kafka writes them. */
    private static Long number(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        if (h == null || h.value() == null) return null;
        byte[] v = h.value();
        if (v.length == Integer.BYTES) return (long) ByteBuffer.wrap(v).getInt();
        if (v.length == Long.BYTES) return ByteBuffer.wrap(v).getLong();
        return null;
    }
}
