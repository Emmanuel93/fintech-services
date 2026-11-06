package com.fintech.shared.trace;

import com.fintech.shared.trace.kafka.CorrelationProducerInterceptor;
import com.fintech.shared.trace.kafka.CorrelationRecordInterceptor;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationPropagationTest {

    @AfterEach
    void limpia() {
        MDC.clear();
    }

    @Test
    @DisplayName("HTTP: el id del llamador se respeta, se pone en el MDC durante la petición y vuelve en la respuesta")
    void httpKeepsTheCallersId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(Correlation.HTTP_HEADER, "pago-123");
        var response = new MockHttpServletResponse();
        AtomicReference<String> visto = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                visto.set(Correlation.current());
            }
        });

        assertThat(visto.get()).isEqualTo("pago-123");
        assertThat(response.getHeader(Correlation.HTTP_HEADER)).isEqualTo("pago-123");
        assertThat(Correlation.current()).isNull();
    }

    @Test
    @DisplayName("HTTP: un header con saltos de línea no se reusa (no se puede falsificar una línea de log)")
    void httpRejectsAForgedId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(Correlation.HTTP_HEADER, "x\nINFO falso");
        var response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(Correlation.HTTP_HEADER)).isNotEqualTo("x\nINFO falso").hasSize(36);
    }

    @Test
    @DisplayName("Kafka: lo que se publica dentro de un flujo lleva su id; fuera de un flujo, uno nuevo")
    void producerStampsTheFlowsId() {
        var interceptor = new CorrelationProducerInterceptor<String, String>();
        MDC.put(Correlation.MDC_KEY, "flujo-7");
        ProducerRecord<String, String> dentro = interceptor.onSend(new ProducerRecord<>("t", "k", "v"));
        MDC.clear();
        ProducerRecord<String, String> fuera = interceptor.onSend(new ProducerRecord<>("t", "k", "v"));

        assertThat(new String(dentro.headers().lastHeader(Correlation.KAFKA_HEADER).value(), StandardCharsets.UTF_8)).isEqualTo("flujo-7");
        assertThat(fuera.headers().lastHeader(Correlation.KAFKA_HEADER)).isNotNull();
    }

    @Test
    @DisplayName("Kafka: el consumidor procesa bajo el id del mensaje y lo limpia al terminar")
    void consumerRunsUnderTheMessagesId() {
        var interceptor = new CorrelationRecordInterceptor<String, String>();
        var record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        record.headers().add(Correlation.KAFKA_HEADER, "flujo-9".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, null);
        assertThat(Correlation.current()).isEqualTo("flujo-9");
        interceptor.afterRecord(record, null);
        assertThat(Correlation.current()).isNull();
    }

    @Test
    @DisplayName("Kafka: un mensaje sin id se procesa con uno nuevo, y lo que publique lo hereda")
    void aMessageWithoutIdStartsAFlow() {
        var consumidor = new CorrelationRecordInterceptor<String, String>();
        var productor = new CorrelationProducerInterceptor<String, String>();
        consumidor.intercept(new ConsumerRecord<>("t", 0, 0L, "k", "v"), null);
        String id = Correlation.current();

        ProducerRecord<String, String> siguiente = productor.onSend(new ProducerRecord<>("t2", "k", "v"));

        assertThat(id).isNotNull();
        assertThat(new String(siguiente.headers().lastHeader(Correlation.KAFKA_HEADER).value(), StandardCharsets.UTF_8)).isEqualTo(id);
    }
}
