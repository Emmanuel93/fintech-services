package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.CancellationPostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** credit-portfolio.credit-account-cancelled → the memo line reversed and the fee kept out of the invoices (D63). */
@Component
public class CreditAccountCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountCancelledListener.class);

    private final CancellationPostingService cancellations;

    public CreditAccountCancelledListener(CancellationPostingService cancellations) {
        this.cancellations = cancellations;
    }

    @KafkaListener(topics = "credit-portfolio.credit-account-cancelled",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountCancelledListenerContainerFactory")
    public void onMessage(CreditAccountCancelledPayload p) {
        if (p.creditAccountId() == null) {
            log.warn("credit-account-cancelled sin cuenta — ignorado");
            return;
        }
        cancellations.onCreditAccountCancelled(p.creditAccountId(), p.obligorPartyId(), p.occurredOn());
    }
}
