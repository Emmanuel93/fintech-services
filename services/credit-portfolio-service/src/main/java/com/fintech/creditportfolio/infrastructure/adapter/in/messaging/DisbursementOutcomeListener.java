package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.port.in.SettleDisbursementUseCase;
import com.fintech.creditportfolio.application.port.in.SettleDisbursementUseCase.DisbursementFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Cierre del ciclo de desembolso: escucha el resultado de disbursement-service y lo traduce de vuelta
 * al dominio de crédito. Es la contraparte de la instrucción que sale en credit-account-activated.
 *
 * <p>El {@code dispositionId} viaja en el eco opaco del conector ({@code sourceMetadata} o, en su
 * defecto, {@code sourceReference}); disbursement nunca entendió qué significaba, sólo lo devolvió.
 */
@Component
public class DisbursementOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(DisbursementOutcomeListener.class);

    private final SettleDisbursementUseCase settleDisbursement;

    public DisbursementOutcomeListener(SettleDisbursementUseCase settleDisbursement) {
        this.settleDisbursement = settleDisbursement;
    }

    @KafkaListener(
            topics = "${credit-portfolio.topics.disbursement-completed:disbursement.completed}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "disbursementCompletedListenerContainerFactory")
    public void onDisbursementCompleted(DisbursementCompletedPayload event) {
        if (!ours(event.sourceSystem(), event.sourceType())) {
            return;
        }
        UUID dispositionId = dispositionId(event.sourceMetadata(), event.sourceReference());
        if (dispositionId == null) {
            log.warn("disbursement.completed sin dispositionId correlacionable disbursementId={} — ignorado",
                    event.disbursementId());
            return;
        }
        settleDisbursement.onDisbursementCompleted(dispositionId, attempt(null, event.sourceMetadata()),
                event.externalRef());
    }

    @KafkaListener(
            topics = "${credit-portfolio.topics.disbursement-failed:disbursement.failed}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "disbursementFailedListenerContainerFactory")
    public void onDisbursementFailed(DisbursementFailedPayload event) {
        if (!ours(event.sourceSystem(), event.sourceType())) {
            return;
        }
        UUID dispositionId = dispositionId(event.sourceMetadata(), event.sourceReference());
        if (dispositionId == null) {
            log.warn("disbursement.failed sin dispositionId correlacionable disbursementId={} — ignorado",
                    event.disbursementId());
            return;
        }
        settleDisbursement.onDisbursementFailed(new DisbursementFailure(dispositionId,
                attempt(event.attempt(), event.sourceMetadata()), event.failureCode(), event.failureReason(),
                Boolean.TRUE.equals(event.retryable())));
    }

    /**
     * BK-16 · el banco receptor devolvió el dinero.
     *
     * <p>`disbursement.returned` se publicaba y <b>nadie lo escuchaba</b>: el cliente quedaba
     * debiendo un dinero que el banco ya había devuelto, y sólo se descubría en la conciliación.
     */
    @KafkaListener(
            topics = "${credit-portfolio.topics.disbursement-returned:disbursement.returned}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "disbursementReturnedListenerContainerFactory")
    public void onDisbursementReturned(DisbursementReturnedPayload event) {
        if (!ours(event.sourceSystem(), event.sourceType())) {
            return;
        }
        UUID dispositionId = dispositionId(event.sourceMetadata(), event.sourceReference());
        if (dispositionId == null) {
            log.warn("disbursement.returned sin dispositionId correlacionable disbursementId={} — ignorado",
                    event.disbursementId());
            return;
        }
        settleDisbursement.onDisbursementReturned(dispositionId, event.returnReason());
    }

    /**
     * Only this service's dispositions: disbursement pays commission lots and withdrawals on the same topics. A
     * message without its origin (older ones) is let through and correlated by the disposition id, as before.
     */
    private static boolean ours(String sourceSystem, String sourceType) {
        return (sourceSystem == null || "credit-portfolio".equals(sourceSystem))
                && (sourceType == null || "DISPOSITION".equals(sourceType));
    }

    /** The attempt the outcome is about: said in the event, else echoed in the metadata, else the first. */
    private static int attempt(Integer said, Map<String, String> metadata) {
        if (said != null && said > 0) {
            return said;
        }
        String echoed = metadata != null ? metadata.get("attempt") : null;
        try {
            return echoed == null ? 1 : Math.max(1, Integer.parseInt(echoed));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Prefiere el eco explícito de metadata; cae a sourceReference (que este servicio pobló con el id). */
    private static UUID dispositionId(Map<String, String> metadata, String sourceReference) {
        String raw = metadata != null ? metadata.get("dispositionId") : null;
        if (raw == null || raw.isBlank()) {
            raw = sourceReference;
        }
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
