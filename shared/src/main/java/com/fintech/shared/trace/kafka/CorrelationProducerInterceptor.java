package com.fintech.shared.trace.kafka;

import com.fintech.shared.trace.Correlation;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Stamps every outgoing message with the correlation id of the flow that sends it. A message sent outside any flow
 * (a scheduled job, a startup task) starts one: from then on everything it causes carries that id.
 */
public class CorrelationProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        if (record.headers().lastHeader(Correlation.KAFKA_HEADER) == null) {
            String id = Correlation.current();
            record.headers().add(Correlation.KAFKA_HEADER,
                    (id != null ? id : Correlation.newId()).getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // Nothing to do: the id travels with the record.
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
