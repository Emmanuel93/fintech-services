package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.ProcessAgreementExecutedCommand;
import com.fintech.creditportfolio.application.port.in.ProcessAgreementExecutedUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class CollectionAgreementExecutedListener {

    private static final Logger log = LoggerFactory.getLogger(CollectionAgreementExecutedListener.class);

    private final ProcessAgreementExecutedUseCase processAgreementExecutedUseCase;

    public CollectionAgreementExecutedListener(ProcessAgreementExecutedUseCase processAgreementExecutedUseCase) {
        this.processAgreementExecutedUseCase = processAgreementExecutedUseCase;
    }

    @KafkaListener(topics = "collections.agreement-executed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "collectionAgreementExecutedListenerContainerFactory")
    public void onMessage(CollectionAgreementExecutedPayload payload) {
        log.info("agreement-executed received creditAccountId={} type={}",
                payload.creditAccountId(), payload.type());

        BigDecimal newRate = null;
        Integer newTerm = null;
        if (payload.newTerms() != null) {
            newRate = payload.newTerms().newNominalRate();
            newTerm = payload.newTerms().newTermMonths();
        }

        processAgreementExecutedUseCase.process(new ProcessAgreementExecutedCommand(
                payload.agreementId() != null ? payload.agreementId().toString() : null,
                payload.creditAccountId(), payload.type(), payload.forgivenAmount(), newRate, newTerm));
    }
}
