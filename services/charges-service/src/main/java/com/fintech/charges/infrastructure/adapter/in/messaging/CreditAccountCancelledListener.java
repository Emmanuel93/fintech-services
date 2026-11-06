package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.service.CancellationCompensationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** credit-portfolio.credit-account-cancelled → every charge of the credit is reversed and its accrual closed (D63). */
@Component
public class CreditAccountCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountCancelledListener.class);

    private final CancellationCompensationService compensation;

    public CreditAccountCancelledListener(CancellationCompensationService compensation) {
        this.compensation = compensation;
    }

    @KafkaListener(
            topics = "${fintech.charges.topics.credit-account-cancelled:credit-portfolio.credit-account-cancelled}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountCancelledListenerContainerFactory")
    public void onCreditAccountCancelled(CreditAccountCancelledPayload event) {
        if (event.creditAccountId() == null) {
            log.warn("credit-account-cancelled sin cuenta — ignorado");
            return;
        }
        log.info("credit-account-cancelled received creditAccountId={} reason={}", event.creditAccountId(), event.reason());
        compensation.onCreditAccountCancelled(event.creditAccountId(), event.reason());
    }
}
