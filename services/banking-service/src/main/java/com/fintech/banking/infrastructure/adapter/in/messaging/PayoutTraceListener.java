package com.fintech.banking.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Los hechos de disbursement que enhebran la traza: el despacho al conector y la aceptación.
 *
 * <p>Banking no conoce el dominio de crédito, y no empieza a conocerlo aquí: de cada hecho toma el
 * id del pago, el importe, la cuenta de salida y —en eco, tal como lo manda quien pidió el pago— la
 * referencia de origen. Lo demás se ignora. En particular las CLABEs completas que viajan en el
 * despacho: se deserializan porque vienen en el mensaje, pero no se declaran, no se guardan y no se
 * registran.
 */
@Component
public class PayoutTraceListener {

    private static final Logger log = LoggerFactory.getLogger(PayoutTraceListener.class);

    private final PayoutTraceRecorder traces;

    public PayoutTraceListener(PayoutTraceRecorder traces) {
        this.traces = traces;
    }

    /** REQUESTED + ROUTED: disbursement entregó la orden al conector por la cuenta de tesorería. */
    @KafkaListener(
            topics = "${fintech.banking.topics.payment-requested:disbursement.stp-requested}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentRequestedListenerContainerFactory")
    public void onPaymentRequested(PaymentRequestedPayload event) {
        if (event.paymentRequestId() == null) {
            log.warn("Despacho al conector sin id de pago — no hay traza que abrir");
            return;
        }
        traces.routed(event.paymentRequestId(), event.amount(), event.orderingAccountId(),
                "disbursement.stp-requested:" + event.paymentRequestId());
    }

    /** Origen + DISPATCHED: el proveedor tomó la orden y le dio clave de rastreo. */
    @KafkaListener(
            topics = "${fintech.banking.topics.disbursement-accepted:disbursement.accepted}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "disbursementAcceptedListenerContainerFactory")
    public void onDisbursementAccepted(DisbursementAcceptedPayload event) {
        if (event.disbursementId() == null) {
            log.warn("disbursement.accepted sin id de pago — no hay traza que abrir");
            return;
        }
        traces.accepted(event.disbursementId(), event.sourceSystem(), event.sourceReference(),
                creditAccountOf(event), event.amount(), event.externalRef(),
                "disbursement.accepted:" + event.disbursementId());
    }

    /**
     * El crédito sale del eco de metadatos que disbursement devuelve sin entenderlo. Una orden que no
     * viene de cartera no lo trae, y su traza simplemente no aparece por crédito — que es correcto.
     */
    static UUID creditAccountOf(DisbursementAcceptedPayload e) {
        String raw = e.sourceMetadata() == null ? null : e.sourceMetadata().get("creditAccountId");
        if (raw == null || raw.isBlank() || "null".equals(raw)) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException noEsUnId) {
            return null;
        }
    }

    /**
     * Lo que banking lee de {@code disbursement.stp-requested}: el id del pago (que el conector usa
     * como {@code paymentRequestId}), el importe y la cuenta propia por la que sale.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentRequestedPayload(UUID paymentRequestId,
                                          UUID companyId,
                                          BigDecimal amount,
                                          UUID orderingAccountId,
                                          Instant occurredOn) {}

    /** Lo que banking lee de {@code disbursement.accepted}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisbursementAcceptedPayload(UUID disbursementId,
                                              String sourceSystem,
                                              String sourceType,
                                              String sourceReference,
                                              Map<String, String> sourceMetadata,
                                              BigDecimal amount,
                                              String externalRef,
                                              Instant occurredOn) {}
}
