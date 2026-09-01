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
        // El tipo que venga en el payload NO se registra ni se usa: lo decide el producto (BK-13).
        log.info("disposition-requested recibida creditAccountId={} amount={}",
                payload.creditAccountId(), payload.amount());

        processDispositionUseCase.process(new ProcessDispositionCommand(
                payload.dispositionRequestId() != null ? payload.dispositionRequestId().toString() : null,
                payload.creditAccountId(),
                payload.obligorPartyId(),
                payload.amount(),
                payload.beneficiaryPartyId(),
                payload.payeeAccount(),
                payload.termPeriods()));
    }
}
