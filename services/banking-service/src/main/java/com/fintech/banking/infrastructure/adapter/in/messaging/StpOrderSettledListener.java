package com.fintech.banking.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.banking.application.service.InternalMovementProjector;
import com.fintech.banking.domain.InternalMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Un pago salió y el proveedor lo liquidó: es un <b>cargo</b> que el banco va a reportar.
 *
 * <p>Proyectarlo aquí es lo que hace posible el cruce determinista: la clave de rastreo que STP
 * asigna es la misma que aparecerá en el estado de cuenta.
 *
 * <p><b>Descartaba todas las liquidaciones.</b> El payload declarado aquí esperaba {@code eventId},
 * {@code amount} y {@code businessDate}, y el conector no mandaba ninguno: cada mensaje llegaba con
 * tres nulos y terminaba en «sin clave o importe id=null — no se proyecta». Ahora el conector los
 * manda (es quien conoce el importe de la orden) y este lado tolera a un emisor viejo: sin id se
 * deriva uno estable de la orden, y sin fecha de operación se toma la de la liquidación en hora del
 * centro, que es el día Banxico en que ocurrió. Sin importe sigue sin proyectarse — inventarlo
 * sería peor que no cruzar —, pero la traza sí avanza: el dinero llegó, lo sepa o no la conciliación.
 */
@Component
public class StpOrderSettledListener {

    private static final Logger log = LoggerFactory.getLogger(StpOrderSettledListener.class);

    /** El día de operación de SPEI es el de la Ciudad de México, no el del servidor. */
    private static final ZoneId BANXICO = ZoneId.of("America/Mexico_City");

    private final InternalMovementProjector proyector;
    private final PayoutTraceRecorder traces;

    public StpOrderSettledListener(InternalMovementProjector proyector, PayoutTraceRecorder traces) {
        this.proyector = proyector;
        this.traces    = traces;
    }

    @KafkaListener(
            topics = "stp.order-settled",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "stpOrderSettledListenerContainerFactory")
    public void onOrderSettled(StpOrderSettledPayload event) {
        if (event.paymentRequestId() == null || event.trackingKey() == null) {
            // Sin orden ni clave no hay de quién hablar. Se registra: un hecho que llega incompleto
            // es un hecho que alguien tiene que mirar, no uno que se descarta callado.
            log.warn("stp.order-settled sin orden o sin clave id={} — se ignora", event.eventId());
            return;
        }
        String eventId = eventIdOf(event);
        LocalDate businessDate = date(event);

        if (event.amount() == null || event.amount().signum() <= 0) {
            log.warn("stp.order-settled sin importe orden={} clave={} — no se proyecta para conciliar",
                    event.paymentRequestId(), event.trackingKey());
        } else {
            proyector.project(InternalMovement.from("STP_ORDER", event.paymentRequestId().toString(),
                    event.amount(), businessDate, event.trackingKey(), "DEBIT", eventId));
        }

        traces.settled(event.paymentRequestId(), event.amount(), event.trackingKey(), businessDate,
                event.settledAt() == null ? null : event.settledAt().atOffset(ZoneOffset.UTC), eventId);
    }

    /** Estable por orden: una reentrega trae el mismo id y la proyección la reconoce. */
    static String eventIdOf(StpOrderSettledPayload e) {
        if (e.eventId() != null && !e.eventId().isBlank()) return e.eventId();
        return UUID.nameUUIDFromBytes(("stp.order-settled:" + e.paymentRequestId())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** La fecha de negocio de la orden, no la de proceso: el banco reporta por fecha de operación. */
    static LocalDate date(StpOrderSettledPayload e) {
        if (e.businessDate() != null) return e.businessDate();
        Instant when = e.settledAt() != null ? e.settledAt() : e.occurredOn();
        return when != null ? when.atZone(BANXICO).toLocalDate() : LocalDate.now(BANXICO);
    }

    /**
     * Lo que publica el conector en {@code stp.order-settled}, con sus nombres.
     *
     * <p>Se declara sólo lo que se lee; el resto (el CEP, el nombre que trae, cómo se observó) se
     * ignora en vez de romper la deserialización cuando el conector crezca su payload.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StpOrderSettledPayload(String eventId,
                                         UUID paymentRequestId,
                                         UUID companyId,
                                         String trackingKey,
                                         BigDecimal amount,
                                         LocalDate businessDate,
                                         Instant settledAt,
                                         Instant occurredOn) {}
}
