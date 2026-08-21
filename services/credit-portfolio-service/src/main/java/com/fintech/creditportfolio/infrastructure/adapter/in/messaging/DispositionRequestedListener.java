package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.ProcessDispositionCommand;
import com.fintech.creditportfolio.application.port.in.ProcessDispositionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DispositionRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionRequestedListener.class);

    private final ProcessDispositionUseCase processDispositionUseCase;

    public DispositionRequestedListener(ProcessDispositionUseCase processDispositionUseCase) {
        this.processDispositionUseCase = processDispositionUseCase;
    }

    @KafkaListener(topics = "wallet.disposition-requested",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dispositionRequestedListenerContainerFactory")
    public void onMessage(DispositionRequestedPayload payload) {
        log.info("disposition-requested received creditAccountId={} amount={} type={}",
                payload.creditAccountId(), payload.amount(), payload.dispositionType());

        processDispositionUseCase.process(new ProcessDispositionCommand(
                payload.dispositionRequestId() != null ? payload.dispositionRequestId().toString() : null,
                payload.creditAccountId(),
                payload.obligorPartyId(),
                payload.amount(),
                payload.dispositionType(),
                payload.beneficiaryPartyId(),
                payload.payeeAccount(),
                payload.termPeriods()));
    }
}
