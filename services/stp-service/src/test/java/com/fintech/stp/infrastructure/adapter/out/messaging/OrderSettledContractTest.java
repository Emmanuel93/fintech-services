package com.fintech.stp.infrastructure.adapter.out.messaging;

import com.fintech.shared.testing.EventContract;
import com.fintech.stp.domain.ObservedVia;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.domain.TrackingKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El contrato de {@code stp.order-settled} con sus dos consumidores.
 *
 * <p><b>Banking descartaba todas las liquidaciones.</b> Esperaba {@code eventId}, {@code amount} y
 * {@code businessDate}; este payload no mandaba ninguno. Nada fallaba al compilar ni al probar: el
 * listener deserializaba un objeto con tres nulos, registraba «sin clave o importe» y seguía. La
 * conciliación se quedó sin un solo movimiento interno contra el cual cruzar.
 *
 * <p>Aquí se serializa el payload <b>real</b> —el que arma el publicador a partir de una orden— y se
 * deserializa con una réplica de lo que declara cada consumidor. Renombrar un campo de este lado
 * pone la prueba en rojo.
 */
class OrderSettledContractTest {

    /** Lo que declara {@code banking}'s {@code StpOrderSettledListener}, con sus nombres. */
    record LiquidadaComoLaVeBanking(String eventId, UUID paymentRequestId, String trackingKey,
                                    BigDecimal amount, LocalDate businessDate, Instant settledAt) {}

    /**
     * Lo que declara {@code disbursement}: lee {@code paymentRequestId} y {@code trackingKey} por
     * alias, así que aquí van con el nombre del productor, que es el que tiene que seguir llegando.
     */
    record LiquidadaComoLaVeDisbursement(UUID paymentRequestId, String trackingKey, String cepUrl,
                                         boolean beneficiaryNameMatches, Instant settledAt) {}

    private static final LocalDate DIA = LocalDate.of(2026, 10, 30);

    private static StpPaymentOrder orden(UUID paymentRequestId, String importe) {
        return StpPaymentOrder.create(paymentRequestId, UUID.randomUUID(), UUID.randomUUID(),
                new TrackingKey("KRD2026103000000000000004"), DIA, new BigDecimal(importe),
                "ROSA ELENA IBARRA SALGADO", "646180157000000004", "hash", "40", "IASR900612B0B", 646,
                "DISPOSICION DE CREDITO", null, null, null,
                "646180000000000012", "FINTECH SERVICES SA DE CV SOFOM ENR", "FSE000101AAA", "40", "C1");
    }

    /** Lo que el publicador real manda a Kafka para esa orden. */
    @SuppressWarnings("unchecked")
    private static StpOrderSettledPayload publicado(StpPaymentOrder order) {
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        new KafkaStpEventPublisher(kafka).publishOrderSettled(order, Instant.parse("2026-10-30T07:23:08Z"),
                "https://stub.local/cep/KRD", "ROSA ELENA IBARRA SALGADO", true, ObservedVia.POLL_ORDER);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(KafkaStpEventPublisher.TOPIC_ORDER_SETTLED), anyString(), payload.capture());
        return (StpOrderSettledPayload) payload.getValue();
    }

    @Test
    @DisplayName("banking recibe importe, fecha de operación e id de evento — sin ellos no proyecta el cargo")
    void contratoConBanking() {
        UUID id = UUID.randomUUID();

        var p = EventContract.between(publicado(orden(id, "45000.00")), LiquidadaComoLaVeBanking.class)
                .requires("eventId",          LiquidadaComoLaVeBanking::eventId)
                .requires("paymentRequestId", LiquidadaComoLaVeBanking::paymentRequestId)
                .requires("trackingKey",      LiquidadaComoLaVeBanking::trackingKey)
                .requires("amount",           LiquidadaComoLaVeBanking::amount)
                .requires("businessDate",     LiquidadaComoLaVeBanking::businessDate)
                .verify();

        assertThat(p.amount()).isEqualByComparingTo("45000.00");
        // El día Banxico de la orden, no el de la observación: así lo reporta el banco.
        assertThat(p.businessDate()).isEqualTo(DIA);
    }

    @Test
    @DisplayName("disbursement sigue recibiendo lo que cierra la orden")
    void contratoConDisbursement() {
        EventContract.between(publicado(orden(UUID.randomUUID(), "45000.00")), LiquidadaComoLaVeDisbursement.class)
                .requires("paymentRequestId", LiquidadaComoLaVeDisbursement::paymentRequestId)
                .requires("trackingKey",      LiquidadaComoLaVeDisbursement::trackingKey)
                .requires("cepUrl",           LiquidadaComoLaVeDisbursement::cepUrl)
                .requires("settledAt",        LiquidadaComoLaVeDisbursement::settledAt)
                .verify();
    }

    @Test
    @DisplayName("el id de evento es el mismo para la misma orden: una reentrega no es un hecho nuevo")
    void idEstable() {
        UUID id = UUID.randomUUID();

        assertThat(publicado(orden(id, "100.00")).eventId())
                .isEqualTo(publicado(orden(id, "100.00")).eventId())
                .isNotEqualTo(publicado(orden(UUID.randomUUID(), "100.00")).eventId());
    }

    @Test
    @DisplayName("muchas liquidaciones a la vez: cada una con su id, ninguno repetido")
    void idsConcurrentes() throws Exception {
        int ordenes = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch salida = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Future<?>> tareas = new ArrayList<>();
        for (int i = 0; i < ordenes; i++) {
            tareas.add(pool.submit(() -> {
                salida.await();
                ids.add(publicado(orden(UUID.randomUUID(), "250.00")).eventId());
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> t : tareas) t.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(ids).hasSize(ordenes);
    }
}
