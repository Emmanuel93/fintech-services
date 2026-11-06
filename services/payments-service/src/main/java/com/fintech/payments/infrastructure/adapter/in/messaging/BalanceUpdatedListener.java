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

        // The portfolio applied a payment: its balance-updated carries the payment order id as the
        // source event, and that is what confirms the order. Without this every order stayed PENDING
        // forever, and nothing could be reversed.
        if (("PAYMENT_APPLIED".equals(event.triggerEvent()) || "RECOVERY_PAYMENT".equals(event.triggerEvent()))
                && event.sourceEventId() != null) {
            try {
                paymentService.confirmByEventId(event.sourceEventId());
            } catch (IllegalArgumentException ex) {
                log.debug("balance-updated sourceEventId={} is not a payment order id", event.sourceEventId());
            }
        }
    }
}
