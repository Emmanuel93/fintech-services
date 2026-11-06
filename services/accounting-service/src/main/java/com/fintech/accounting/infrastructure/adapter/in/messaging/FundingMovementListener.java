package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.FundingPostingService;
import com.fintech.accounting.application.service.FundingPostingService.FundingFact;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** The funding treasury recorded: capital paid in, lines drawn, their interest and their repayments. */
@Component
public class FundingMovementListener {

    private final FundingPostingService funding;

    public FundingMovementListener(FundingPostingService funding) {
        this.funding = funding;
    }

    @KafkaListener(topics = "banking.funding-movement-recorded",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "fundingMovementListenerContainerFactory")
    public void onMessage(FundingMovementPayload p) {
        funding.onMovement(new FundingFact(p.eventId(), p.fundingMovementId(), p.fundingSourceId(), p.sourceKind(),
                p.counterpartyName(), p.contractReference(), p.movementType(), p.amount(), p.valueDate(),
                p.accruedFrom(), p.accruedThrough(), p.contributedAfter(), p.principalAfter(),
                p.interestPayableAfter(), p.sourceStatus(), p.termMonths(), p.occurredOn()));
    }
}
