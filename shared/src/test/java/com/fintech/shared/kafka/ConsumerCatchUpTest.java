package com.fintech.shared.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsumerCatchUpTest {

    private static final String TOPICO = "charges.charge-applied";
    private static final TopicPartition P0 = new TopicPartition(TOPICO, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPICO, 1);

    private Admin admin;
    private final Map<TopicPartition, Long> finales = new HashMap<>();
    private final Map<TopicPartition, Long> inicios = new HashMap<>();
    private final Map<TopicPartition, OffsetAndMetadata> confirmados = new HashMap<>();
    private ConsumerCatchUp catchUp;

    @BeforeEach
    void broker() {
        admin = mock(Admin.class);
        DescribeTopicsResult topics = mock(DescribeTopicsResult.class);
        when(admin.describeTopics(anyCollection())).thenReturn(topics);
        when(topics.topicNameValues()).thenAnswer(i -> Map.of(
                TOPICO, KafkaFuture.completedFuture(new TopicDescription(TOPICO, false, List.of(
                        new TopicPartitionInfo(0, null, List.of(), List.of()),
                        new TopicPartitionInfo(1, null, List.of(), List.of())))),
                "no.existe", fallido(new UnknownTopicOrPartitionException("no existe"))));

        when(admin.listOffsets(anyMap())).thenAnswer(i -> {
            Map<TopicPartition, OffsetSpec> pedido = i.getArgument(0);
            Map<TopicPartition, ListOffsetsResultInfo> out = new HashMap<>();
            pedido.forEach((tp, spec) -> out.put(tp, new ListOffsetsResultInfo(
                    (spec instanceof OffsetSpec.EarliestSpec ? inicios : finales).getOrDefault(tp, 0L),
                    0L, Optional.empty())));
            ListOffsetsResult r = mock(ListOffsetsResult.class);
            when(r.all()).thenReturn(KafkaFuture.completedFuture(out));
            return r;
        });

        when(admin.listConsumerGroupOffsets(anyString())).thenAnswer(i -> {
            ListConsumerGroupOffsetsResult r = mock(ListConsumerGroupOffsetsResult.class);
            when(r.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(new HashMap<>(confirmados)));
            return r;
        });
        catchUp = new ConsumerCatchUp(() -> admin);
    }

    @Test
    @DisplayName("el grupo que confirmó hasta el final no tiene nada pendiente")
    void alDia() {
        finales.put(P0, 10L); finales.put(P1, 4L);
        confirmados.put(P0, new OffsetAndMetadata(10)); confirmados.put(P1, new OffsetAndMetadata(4));

        var r = catchUp.await("credit-portfolio-service", List.of(TOPICO), Duration.ZERO, Duration.ofMillis(10));

        assertThat(r.caughtUp()).isTrue();
        assertThat(r.pending()).isZero();
    }

    @Test
    @DisplayName("lo que falta se suma por partición; sin offset confirmado le falta todo lo retenido")
    void cuentaLoPendiente() {
        finales.put(P0, 10L); finales.put(P1, 7L);
        inicios.put(P1, 2L);
        confirmados.put(P0, new OffsetAndMetadata(6));

        var r = catchUp.await("g", List.of(TOPICO), Duration.ZERO, Duration.ofMillis(10));

        assertThat(r.caughtUp()).isFalse();
        assertThat(r.pending()).as("4 de la 0 y 5 de la 1 (7 menos los 2 que ya no están)").isEqualTo(9);
    }

    @Test
    @DisplayName("un tópico que todavía no existe no tiene nada que esperar")
    void topicoInexistente() {
        var r = catchUp.await("g", List.of("no.existe"), Duration.ZERO, Duration.ofMillis(10));
        assertThat(r.caughtUp()).isTrue();
    }

    @Test
    @DisplayName("la foto se toma al empezar: lo que se publica después no se espera")
    void laFotoNoSePersigue() {
        finales.put(P0, 10L); finales.put(P1, 0L);
        confirmados.put(P0, new OffsetAndMetadata(8));
        AtomicLong vueltas = new AtomicLong();
        // Cada consulta: el grupo avanza uno y el tópico crece dos. Persiguiendo el final no
        // terminaría; contra la foto (10) termina en la segunda vuelta.
        when(admin.listConsumerGroupOffsets(anyString())).thenAnswer(i -> {
            long n = vueltas.incrementAndGet();
            finales.put(P0, 10L + 2 * n);
            ListConsumerGroupOffsetsResult r = mock(ListConsumerGroupOffsetsResult.class);
            when(r.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(
                    Map.of(P0, new OffsetAndMetadata(8 + n), P1, new OffsetAndMetadata(0))));
            return r;
        });

        var r = catchUp.await("g", List.of(TOPICO), Duration.ofSeconds(5), Duration.ofMillis(10));

        assertThat(r.caughtUp()).isTrue();
        assertThat(vueltas.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("si no alcanza dentro del plazo, dice cuánto le faltó")
    void plazoVencido() {
        finales.put(P0, 100L); finales.put(P1, 0L);
        confirmados.put(P0, new OffsetAndMetadata(40));

        var r = catchUp.await("g", List.of(TOPICO), Duration.ofMillis(120), Duration.ofMillis(20));

        assertThat(r.caughtUp()).isFalse();
        assertThat(r.pending()).isEqualTo(60);
        assertThat(r.waited()).isGreaterThanOrEqualTo(Duration.ofMillis(120));
    }

    @Test
    @DisplayName("rendimiento: mil consultas en menos de un segundo")
    void rapido() {
        for (int p = 0; p < 2; p++) {
            finales.put(new TopicPartition(TOPICO, p), 1_000_000L);
            confirmados.put(new TopicPartition(TOPICO, p), new OffsetAndMetadata(1_000_000L));
        }
        long inicio = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            assertThat(catchUp.await("g", List.of(TOPICO), Duration.ZERO, Duration.ofMillis(1)).caughtUp()).isTrue();
        }
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(1_000L);
    }

    private static KafkaFuture<TopicDescription> fallido(Throwable t) {
        KafkaFutureImpl<TopicDescription> f = new KafkaFutureImpl<>();
        f.completeExceptionally(t);
        return f;
    }
}
