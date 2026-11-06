package com.fintech.payments.infrastructure.adapter.out.messaging;

import com.fintech.payments.application.port.out.PaymentEventPublisher;
import com.fintech.payments.domain.PaymentOrder;
import com.fintech.payments.domain.balance.CustomerBalanceMovement;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Publishes the payment facts once the order that carries them is committed, not before.
 *
 * <p>credit-portfolio answers a payment-applied in milliseconds with the balance-updated (or payment-rejected) that
 * confirms (or rejects) the order by its id. Sent inside the transaction, the answer could arrive before the order
 * was committed: payments looked the order up, did not find it, and the order stayed PENDING for good while the
 * payment was already in the balance (demo 3 Nov: order 2aa63c3c, SPEI 913.19, applied on 15 Apr, never confirmed).
 * The send is registered as a {@link TransactionSynchronization} and leaves only if the transaction committed; a
 * rollback no longer leaves a payment circulating that the database denies.
 */
@Component
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    static final String TOPIC_PAYMENT_APPLIED  = "payments.payment-applied";
    static final String TOPIC_PAYMENT_REVERSED = "payments.payment-returned";
    static final String TOPIC_BALANCE_MOVED    = "payments.customer-balance-moved";

    private static final Logger log = LoggerFactory.getLogger(KafkaPaymentEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPaymentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishPaymentApplied(String eventId, UUID creditAccountId,
                                       BigDecimal amount, long snapshotVersion) {
        var payload = new PaymentAppliedOutboundPayload(eventId, creditAccountId, amount, snapshotVersion,
                BusinessClock.current().today());
        afterCommit(() -> kafkaTemplate.send(TOPIC_PAYMENT_APPLIED, creditAccountId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("payment-applied publish failed eventId={}: {}", eventId, ex.getMessage());
                    } else {
                        log.info("payment-applied published eventId={} creditAccountId={} amount={} v={}",
                                eventId, creditAccountId, amount, snapshotVersion);
                    }
                }));
    }

    @Override
    public void publishPaymentApplied(PaymentOrder order) {
        ZoneId zone = BusinessClock.current().zone();
        Instant receivedAt = order.getReceivedAt() != null ? order.getReceivedAt() : order.getCreatedAt();
        var payload = new PaymentAppliedOutboundPayload(order.getPaymentOrderId().toString(),
                order.getCreditAccountId(), order.getAmount(), order.getSnapshotVersion(),
                LocalDate.ofInstant(receivedAt, zone), order.getObligorPartyId(), receivedAt,
                order.getOperationDate(), order.fundingSource().name(), order.getInboundCreditId(),
                order.getAllocationSeq(), order.getAllocationMode(), order.getTrackingKey());
        afterCommit(() -> kafkaTemplate.send(TOPIC_PAYMENT_APPLIED, order.getCreditAccountId().toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("payment-applied publish failed eventId={}: {}", payload.eventId(), ex.getMessage());
                    } else {
                        log.info("payment-applied published eventId={} creditAccountId={} amount={} fuente={} modo={}",
                                payload.eventId(), payload.creditAccountId(), payload.amount(),
                                payload.fundingSource(), payload.allocationMode());
                    }
                }));
    }

    @Override
    public void publishBalanceMoved(CustomerBalanceMovement m) {
        var payload = new CustomerBalanceMovedPayload("BALANCE-MOVE:" + m.getMovementId(), m.getMovementId(),
                m.getPartyId(), m.type().name(), m.getAmount(), m.getBalanceAfter(), m.getInboundCreditId(),
                m.getPaymentOrderId(), m.getCreditAccountId(), m.getBusinessDate(), m.getOccurredAt());
        afterCommit(() -> kafkaTemplate.send(TOPIC_BALANCE_MOVED, m.getPartyId().toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) log.error("customer-balance-moved publish failed {}: {}", payload.eventId(), ex.getMessage());
                }));
    }

    @Override
    public void publishPaymentReversed(String eventId, UUID creditAccountId,
                                        BigDecimal amount, String reason) {
        var payload = new PaymentReversedOutboundPayload(eventId, creditAccountId, amount, reason,
                BusinessClock.current().today());
        afterCommit(() -> kafkaTemplate.send(TOPIC_PAYMENT_REVERSED, creditAccountId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("payment-returned publish failed eventId={}: {}", eventId, ex.getMessage());
                    } else {
                        log.info("payment-returned published eventId={} creditAccountId={} amount={}",
                                eventId, creditAccountId, amount);
                    }
                }));
    }

    /** Runs the send after the surrounding transaction commits; at once when there is none. */
    private static void afterCommit(Runnable send) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
            return;
        }
        send.run();
    }
}
