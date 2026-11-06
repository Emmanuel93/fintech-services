package com.fintech.commission.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.commission.application.service.LotPayoutOutcomeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What disbursement reports of a lot's SPEI. Disbursement echoes what commission sent: the system ({@code commission}),
 * and in the metadata the lot and the attempt. Every other source's outcomes are not commission's.
 */
@Component
public class LotPayoutOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(LotPayoutOutcomeListener.class);
    static final String SOURCE_SYSTEM = "commission";

    private final LotPayoutOutcomeService outcomes;

    public LotPayoutOutcomeListener(LotPayoutOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @KafkaListener(topics = "${fintech.commission.topics.disbursement-completed:disbursement.completed}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "disbursementOutcomeListenerContainerFactory")
    public void onCompleted(DisbursementOutcomePayload p) {
        if (!p.isCommissions()) return;
        outcomes.onPaid(p.batchId(), p.externalRef(), p.settledAt());
    }

    @KafkaListener(topics = "${fintech.commission.topics.disbursement-failed:disbursement.failed}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "disbursementOutcomeListenerContainerFactory")
    public void onFailed(DisbursementOutcomePayload p) {
        if (!p.isCommissions()) return;
        outcomes.onFailed(p.batchId(), p.attempt(), "rechazado: " + (p.failureCode() != null ? p.failureCode() : "")
                + (p.failureReason() != null ? " " + p.failureReason() : ""));
    }

    @KafkaListener(topics = "${fintech.commission.topics.disbursement-returned:disbursement.returned}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "disbursementOutcomeListenerContainerFactory")
    public void onReturned(DisbursementOutcomePayload p) {
        if (!p.isCommissions()) return;
        outcomes.onFailed(p.batchId(), p.attempt(), "devuelto: " + (p.returnCauseCode() != null ? p.returnCauseCode() : ""));
    }

    /** The fields of disbursement's completed, failed and returned facts commission reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisbursementOutcomePayload(String sourceSystem, String sourceReference, Map<String, String> sourceMetadata,
                                             String externalRef, Instant settledAt, String failureCode,
                                             String failureReason, String returnCauseCode) {

        boolean isCommissions() {
            return SOURCE_SYSTEM.equals(sourceSystem) && batchId() != null;
        }

        UUID batchId() {
            String id = sourceMetadata != null ? sourceMetadata.get("batchId") : null;
            if (id == null && sourceReference != null && sourceReference.startsWith("LOTE:")) id = sourceReference.substring(5);
            try {
                return id == null ? null : UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                log.warn("Resultado de dispersión de comisiones con lote ilegible: {}", id);
                return null;
            }
        }

        int attempt() {
            String a = sourceMetadata != null ? sourceMetadata.get("attempt") : null;
            try {
                return a == null ? 1 : Integer.parseInt(a);
            } catch (NumberFormatException e) {
                return 1;
            }
        }
    }
}
