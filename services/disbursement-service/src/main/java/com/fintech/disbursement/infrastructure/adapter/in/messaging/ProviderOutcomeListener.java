package com.fintech.disbursement.infrastructure.adapter.in.messaging;

import com.fintech.disbursement.application.ProviderOutcome;
import com.fintech.disbursement.application.port.in.ApplyProviderOutcomeUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Traduce lo que reporta un conector a {@link ProviderOutcome} y se lo entrega al núcleo.
 *
 * <p>Los topics son configurables: añadir un segundo proveedor es apuntar otro conector a los mismos
 * nombres, o añadir cuatro entradas de configuración. En ninguno de los dos casos cambia el núcleo.
 */
@Component
public class ProviderOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(ProviderOutcomeListener.class);

    private final ApplyProviderOutcomeUseCase applyOutcome;

    public ProviderOutcomeListener(ApplyProviderOutcomeUseCase applyOutcome) {
        this.applyOutcome = applyOutcome;
    }

    @KafkaListener(
            topics = "${fintech.disbursement.topics.provider-outcomes.accepted}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "providerAcceptedListenerContainerFactory")
    public void onAccepted(ProviderOutcomePayloads.Accepted payload) {
        log.debug("Conector aceptó disbursementId={} externalRef={}",
                payload.disbursementId(), payload.externalRef());
        applyOutcome.apply(new ProviderOutcome.Accepted(
                payload.disbursementId(),
                payload.externalRef() != null ? payload.externalRef() : payload.providerOrderId(),
                orNow(payload.occurredOn())));
    }

    @KafkaListener(
            topics = "${fintech.disbursement.topics.provider-outcomes.settled}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "providerSettledListenerContainerFactory")
    public void onSettled(ProviderOutcomePayloads.Settled payload) {
        log.debug("Conector liquidó disbursementId={}", payload.disbursementId());
        applyOutcome.apply(new ProviderOutcome.Settled(
                payload.disbursementId(), payload.externalRef(), payload.receiptUrl(),
                payload.beneficiaryNameMatches(), orNow(payload.settledAt())));
    }

    @KafkaListener(
            topics = "${fintech.disbursement.topics.provider-outcomes.rejected}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "providerRejectedListenerContainerFactory")
    public void onRejected(ProviderOutcomePayloads.Rejected payload) {
        log.info("Conector rechazó disbursementId={} code={} retryable={}",
                payload.disbursementId(), payload.providerCode(), payload.retryable());
        applyOutcome.apply(new ProviderOutcome.Rejected(
                payload.disbursementId(), payload.providerCode(),
                reason(payload), payload.retryable(), orNow(payload.occurredOn())));
    }

    @KafkaListener(
            topics = "${fintech.disbursement.topics.provider-outcomes.returned}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "providerReturnedListenerContainerFactory")
    public void onReturned(ProviderOutcomePayloads.Returned payload) {
        log.info("Devolución reportada disbursementId={} causa={}",
                payload.disbursementId(), payload.returnCauseCode());
        applyOutcome.apply(new ProviderOutcome.Returned(
                payload.disbursementId(), payload.returnCauseCode(), orNow(payload.occurredOn())));
    }

    private static String reason(ProviderOutcomePayloads.Rejected payload) {
        if (payload.detail() != null && !payload.detail().isBlank()) {
            return payload.providerReason() + " — " + payload.detail();
        }
        return payload.providerReason();
    }

    private static Instant orNow(Instant value) {
        return value != null ? value : Instant.now();
    }
}
