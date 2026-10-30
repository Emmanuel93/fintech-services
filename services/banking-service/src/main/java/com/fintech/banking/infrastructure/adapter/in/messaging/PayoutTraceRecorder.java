package com.fintech.banking.infrastructure.adapter.in.messaging;

import com.fintech.banking.application.service.MovementTraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Traduce los hechos del carril del dinero a tramos de la traza.
 *
 * <p><b>Existe porque nadie llamaba a {@code MovementTraceService}.</b> La traza tenía servicio,
 * tabla, controlador y pruebas, y cero productores: {@code /traces/by-credit-account/{id}} contestaba
 * siempre una lista vacía. Estos son los hechos que la alimentan, cada uno con lo que sabe:
 *
 * <pre>
 * REQUESTED + ROUTED   disbursement.stp-requested  el despacho al conector, con la cuenta que eligió tesorería
 * (origen) + DISPATCHED disbursement.accepted       el proveedor tomó la orden; trae de qué crédito viene y la clave
 * DISPATCHED + SETTLED  stp.order-settled           la liquidación; completa la fecha de operación
 * RECONCILED            —                           sin productor: no se ingieren cargos del estado de cuenta
 * POSTED                —                           sin productor: la póliza no lleva el id del pago
 * </pre>
 *
 * <p><b>Cada hecho abre la traza si no existe.</b> Kafka no ordena entre topics, y el que llega
 * primero no siempre es el despacho. Todos traen el id del pago —que es real, no se inventa nada—, y
 * el que sabe más completa al que sabía menos sin pisarlo.
 */
@Component
public class PayoutTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(PayoutTraceRecorder.class);

    private final MovementTraceService traces;

    public PayoutTraceRecorder(MovementTraceService traces) {
        this.traces = traces;
    }

    /** Disbursement entregó la orden al conector por la cuenta que decidió tesorería. */
    public void routed(UUID payoutId, BigDecimal amount, UUID bankAccountId, String eventId) {
        open(payoutId, null, null, null, amount, eventId);
        if (bankAccountId != null) {
            retrying(payoutId, () -> traces.onRoute(payoutId, bankAccountId, eventId));
        }
    }

    /** El proveedor tomó la orden. Es el hecho que sabe de qué crédito viene el pago. */
    public void accepted(UUID payoutId, String sourceSystem, String sourceReference,
                         UUID creditAccountId, BigDecimal amount, String trackingKey, String eventId) {
        open(payoutId, sourceSystem, sourceReference, creditAccountId, amount, eventId);
        if (trackingKey != null && !trackingKey.isBlank()) {
            retrying(payoutId, () -> traces.onDispatch(payoutId, trackingKey, null, eventId));
        }
    }

    /** El conector confirmó la liquidación. */
    public void settled(UUID payoutId, BigDecimal amount, String trackingKey, LocalDate businessDate,
                        OffsetDateTime settledAt, String eventId) {
        open(payoutId, null, null, null, amount, eventId);
        retrying(payoutId, () -> traces.onDispatch(payoutId, trackingKey, businessDate, eventId));
        retrying(payoutId, () -> traces.onSettle(payoutId, settledAt, eventId));
    }

    private void open(UUID payoutId, String sourceSystem, String sourceReference,
                      UUID creditAccountId, BigDecimal amount, String eventId) {
        retrying(payoutId, () -> traces.onRequest(
                payoutId, sourceSystem, sourceReference, creditAccountId, amount, eventId));
    }

    private static final int INTENTOS = 5;

    /**
     * Cada tramo en su transacción, reintentado si perdió una carrera con otro hecho del mismo pago.
     *
     * <p>Dos carreras posibles, las dos sanas: dos hechos ven «no existe» y el segundo choca con el
     * índice único al abrir; o dos tocan la misma traza y el segundo pierde por versión. En ambas,
     * cuando el perdedor se entera el ganador ya confirmó, así que reintentar entra por la rama que
     * completa sobre lo que ya hay. Dejarlo al reintento de Kafka también convergería, pero con una
     * traza de error en el log por algo que no lo es. Los tramos son idempotentes: repetir no
     * duplica ni retrocede nada.
     */
    private void retrying(UUID payoutId, Runnable tramo) {
        for (int intento = 1; ; intento++) {
            try {
                tramo.run();
                return;
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException carrera) {
                if (intento >= INTENTOS) throw carrera;
                log.debug("Traza del pago {}: otro hecho la tocó en el mismo instante (intento {}) — se reintenta",
                        payoutId, intento);
            }
        }
    }
}
