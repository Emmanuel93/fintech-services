package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.service.BalanceReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Routes inbound balance-affecting events from charges/payments/collections into the
 * balance engine. The engine (not these listeners) owns idempotency + audit + republish.
 */
@Component
public class BalanceEventListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceEventListener.class);

    private final BalanceReconciliationService reconciliation;

    public BalanceEventListener(BalanceReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @KafkaListener(topics = "charges.charge-applied",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "chargeAppliedListenerContainerFactory")
    public void onChargeApplied(ChargeAppliedPayload p) {
        log.info("charge-applied accountId={} type={} amount={}", p.creditAccountId(), p.chargeType(), p.totalAmount());
        reconciliation.onChargeApplied(p.eventId(), p.creditAccountId(), p.chargeType(), p.totalAmount(),
                p.effectiveDate());
    }

    @KafkaListener(topics = "charges.charge-reversed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "chargeReversedListenerContainerFactory")
    public void onChargeReversed(ChargeReversedPayload p) {
        log.info("charge-reversed accountId={} original={} amount={} waived={}",
                p.creditAccountId(), p.originalChargeType(), p.amount(), p.waived());
        reconciliation.onChargeReversed(p.eventId(), p.creditAccountId(), p.originalChargeType(), p.amount(), p.waived());
    }

    @KafkaListener(topics = "payments.payment-applied",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentAppliedListenerContainerFactory")
    public void onPaymentApplied(PaymentAppliedPayload p) {
        log.info("payment-applied accountId={} amount={}", p.creditAccountId(), p.amount());
        reconciliation.onPaymentApplied(p.eventId(), p.creditAccountId(), p.amount());
    }

    @KafkaListener(topics = "payments.payment-returned",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentReturnedListenerContainerFactory")
    public void onPaymentReturned(PaymentReturnedPayload p) {
        log.info("payment-returned accountId={} amount={}", p.creditAccountId(), p.amount());
        reconciliation.onPaymentReturned(p.eventId(), p.creditAccountId(), p.amount());
    }

    @KafkaListener(topics = "collections.write-off-executed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "writeOffListenerContainerFactory")
    public void onWriteOffExecuted(WriteOffExecutedPayload p) {
        log.info("write-off-executed accountId={}", p.creditAccountId());
        reconciliation.onWriteOffExecuted(p.eventId(), p.creditAccountId());
    }
}
