package com.fintech.banking.infrastructure.adapter.in.messaging;

import com.fintech.banking.application.service.InternalMovementProjector;
import com.fintech.banking.domain.InternalMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un pago salió y el proveedor lo liquidó: es un <b>cargo</b> que el banco va a reportar.
 *
 * <p>Proyectarlo aquí es lo que hace posible el cruce determinista: la clave de rastreo que STP
 * asigna es la misma que aparecerá en el estado de cuenta.
 */
@Component
public class StpOrderSettledListener {

    private static final Logger log = LoggerFactory.getLogger(StpOrderSettledListener.class);

    private final InternalMovementProjector proyector;

    public StpOrderSettledListener(InternalMovementProjector proyector) {
        this.proyector = proyector;
    }

    @KafkaListener(
            topics = "stp.order-settled",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "stpOrderSettledListenerContainerFactory")
    public void onOrderSettled(StpOrderSettledPayload event) {
        if (event.trackingKey() == null || event.amount() == null) {
            // Sin clave ni importe no hay nada que cruzar. Se registra: un hecho que llega
            // incompleto es un hecho que alguien tiene que mirar, no uno que se descarta callado.
            log.warn("stp.order-settled sin clave o importe id={} — no se proyecta", event.eventId());
            return;
        }
        proyector.proyectar(InternalMovement.de("STP_ORDER", event.paymentRequestId().toString(),
                event.amount(), fecha(event), event.trackingKey(), "DEBIT", event.eventId()));
    }

    /** La fecha de negocio de la orden, no la de proceso: el banco reporta por fecha de operación. */
    private static LocalDate fecha(StpOrderSettledPayload e) {
        return e.businessDate() != null ? e.businessDate() : LocalDate.now();
    }

    public record StpOrderSettledPayload(String eventId,
                                  java.util.UUID paymentRequestId,
                                  String trackingKey,
                                  BigDecimal amount,
                                  LocalDate businessDate) {}
}
