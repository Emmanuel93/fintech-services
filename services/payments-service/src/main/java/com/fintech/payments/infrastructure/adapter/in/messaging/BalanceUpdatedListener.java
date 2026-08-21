package com.fintech.payments.infrastructure.adapter.in.messaging;

import com.fintech.payments.application.service.BalanceSnapshotService;
import com.fintech.payments.application.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BalanceUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpdatedListener.class);

    private final BalanceSnapshotService snapshotService;
    private final PaymentService paymentService;

    public BalanceUpdatedListener(BalanceSnapshotService snapshotService,
                                   PaymentService paymentService) {
        this.snapshotService = snapshotService;
        this.paymentService  = paymentService;
    }

    @KafkaListener(
            topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "balanceUpdatedListenerContainerFactory")
    public void onBalanceUpdated(BalanceUpdatedPayload event) {
        log.debug("balance-updated received creditAccountId={} totalDebt={} v={}",
                event.creditAccountId(), event.totalDebt(), event.balanceVersion());

        snapshotService.upsert(
                event.creditAccountId(), event.obligorPartyId(),
                event.principalBalance(), event.accruedInterestBalance(),
                event.penaltyBalance(), event.availableCredit(),
                event.totalDebt(), event.balanceVersion(), event.accountStatus());

        // If this balance-updated was triggered by PAYMENT_APPLIED, confirm the order
        // (credit-portfolio uses the paymentOrderId as sourceEventId in balance_events)
        // Confirmation is best-effort — the eventId in balance-updated is the balance event ID,
        // not the source payment event ID, so we rely on payment-rejected for failure cases.
    }
}
