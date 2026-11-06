package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.AccountActivation;
import com.fintech.accounting.application.service.FeeDeferralService;
import com.fintech.accounting.application.service.PostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);
    private final PostingService postingService;

    public CreditAccountActivatedListener(PostingService postingService) {
        this.postingService = postingService;
    }

    @KafkaListener(topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onMessage(CreditAccountActivatedPayload p) {
        log.debug("credit-account-activated creditAccountId={} unidad={} producto={}", p.creditAccountId(),
                p.originUnitCode(), p.productType());
        postingService.onAccountActivated(new AccountActivation(
                p.eventId() != null ? p.eventId() : "ACTIVATED-" + p.creditAccountId(),
                p.creditAccountId(), p.obligorPartyId(), p.authorizedAmount(),
                p.principalBalance(), p.originUnitCode(), p.productType(), p.occurredOn(),
                FeeDeferralService.termMonths(p.termPeriods(), p.paymentFrequency())));
    }
}
