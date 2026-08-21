package com.fintech.wallet.infrastructure.adapter.in.messaging;

import com.fintech.wallet.application.service.WalletProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);

    private final WalletProjectionService projectionService;

    public CreditAccountActivatedListener(WalletProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onCreditAccountActivated(CreditAccountActivatedPayload event) {
        log.info("credit-account-activated received creditAccountId={} productType={}",
                event.creditAccountId(), event.productType());

        // For revolving products, creditLimit is the available credit; for installment, null
        boolean isRevolving = "REVOLVING".equals(event.productBehavior())
                || "REVOLVING_CREDIT".equals(event.productType());
        var availableCredit = isRevolving ? event.creditLimit() : null;

        projectionService.onCreditAccountActivated(
                event.creditAccountId(),
                event.obligorPartyId(),
                event.productType(),
                event.principalBalance(),
                availableCredit);
    }
}
