package com.fintech.shared.trace.kafka;

import com.fintech.shared.trace.Correlation;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;
import org.springframework.kafka.listener.BatchInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Each message is handled under the correlation id it carries: it is put in the log context before the listener
 * runs, so the listener's lines and the messages it sends in turn carry the same id, and removed after it.
 * A message without one (published before the id existed, or by a client outside the platform) gets a new one.
 */
public class CorrelationRecordInterceptor<K, V> implements RecordInterceptor<K, V>, BatchInterceptor<K, V> {

    static final String MDC_TOPIC = "kafkaTopic";

    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        String id = of(record.headers());
        MDC.put(Correlation.MDC_KEY, id != null ? id : Correlation.newId());
        MDC.put(MDC_TOPIC, record.topic());
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        clear();
    }

    @Override
    public ConsumerRecords<K, V> intercept(ConsumerRecords<K, V> records, Consumer<K, V> consumer) {
        // A batch has no single flow: the first record's id is the batch's, which is what a batch listener logs under.
        var it = records.iterator();
        if (it.hasNext()) {
            ConsumerRecord<K, V> first = it.next();
            String id = of(first.headers());
            MDC.put(Correlation.MDC_KEY, id != null ? id : Correlation.newId());
            MDC.put(MDC_TOPIC, first.topic());
        }
        return records;
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        clear();
    }

    private static void clear() {
        MDC.remove(Correlation.MDC_KEY);
        MDC.remove(MDC_TOPIC);
    }

    /** The correlation id a record carries, or {@code null}. */
    public static String of(Headers headers) {
        Header h = headers == null ? null : headers.lastHeader(Correlation.KAFKA_HEADER);
        return h == null || h.value() == null ? null : Correlation.clean(new String(h.value(), StandardCharsets.UTF_8));
    }
}
