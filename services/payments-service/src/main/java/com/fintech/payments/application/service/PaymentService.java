package com.fintech.payments.application.service;

import com.fintech.payments.application.PaymentsProperties;
import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.application.port.out.PaymentEventPublisher;
import com.fintech.payments.application.port.out.PaymentOrderRepository;
import com.fintech.payments.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderRepository orderRepository;
    private final AccountBalanceSnapshotRepository snapshotRepository;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentsProperties properties;

    public PaymentService(PaymentOrderRepository orderRepository,
                           AccountBalanceSnapshotRepository snapshotRepository,
                           PaymentEventPublisher eventPublisher,
                           PaymentsProperties properties) {
        this.orderRepository    = orderRepository;
        this.snapshotRepository = snapshotRepository;
        this.eventPublisher     = eventPublisher;
        this.properties         = properties;
    }

    /**
     * Submit a payment.
     *
     * Guards (in order):
     *   PY-01  idempotency — same externalRef → return existing order
     *   PY-05  account must be ACTIVE/SUSPENDED — rejects WRITTEN_OFF/CLOSED
     *   PY-02  amount must be positive
     *   PY-06  overpayment — handled per OverpaymentStrategy property:
     *            RETURN_TO_PAYER: apply only totalDebt; return excess via payment-returned
     *            APPLY_NEXT_INSTALLMENT: forward full amount to credit-portfolio
     *
     * After guards: persists PENDING order and publishes payment-applied to credit-portfolio,
     * which post-validates (authoritative) and publishes balance-updated or payment-rejected.
     */
    public PaymentOrder submit(UUID creditAccountId, UUID obligorPartyId,
                                BigDecimal amount, PaymentMethod method, String externalRef) {

        // PY-01: idempotency
        if (orderRepository.existsByExternalRef(externalRef)) {
            return orderRepository.findByExternalRef(externalRef)
                    .orElseThrow(() -> new PaymentOrderNotFoundException(externalRef));
        }

        AccountBalanceSnapshot snapshot = snapshotRepository.findByCreditAccountId(creditAccountId)
                .orElseThrow(() -> new InvalidPaymentStateException(
                        "No balance snapshot for creditAccountId=" + creditAccountId
                        + ". Account may not be activated yet."));

        // PY-05: account must be in a state that accepts payments
        if (!snapshot.isAccountActive()) {
            throw new InvalidPaymentStateException(
                    "Cannot submit payment: account " + creditAccountId
                    + " is in status " + snapshot.getAccountStatus());
        }

        // PY-02: positive amount (already validated at API layer, but domain re-checks)
        if (!snapshot.canAcceptPayment(amount)) {
            throw new InvalidPaymentStateException("Payment amount must be positive");
        }

        // PY-06: overpayment handling
        BigDecimal totalDebt = snapshot.getTotalDebt();
        boolean isOverpayment = amount.compareTo(totalDebt) > 0 && totalDebt.signum() > 0;

        if (isOverpayment) {
            return handleOverpayment(creditAccountId, obligorPartyId, amount, totalDebt,
                    method, externalRef, snapshot.getBalanceVersion());
        }

        // Normal path
        PaymentOrder order = PaymentOrder.create(creditAccountId, obligorPartyId, amount,
                method, externalRef, snapshot.getBalanceVersion());
        orderRepository.save(order);

        eventPublisher.publishPaymentApplied(order.getPaymentOrderId().toString(),
                creditAccountId, amount, snapshot.getBalanceVersion());

        log.info("Payment submitted orderId={} creditAccountId={} amount={} method={} v={}",
                order.getPaymentOrderId(), creditAccountId, amount, method, snapshot.getBalanceVersion());
        return order;
    }

    private PaymentOrder handleOverpayment(UUID creditAccountId, UUID obligorPartyId,
                                            BigDecimal requestedAmount, BigDecimal totalDebt,
                                            PaymentMethod method, String externalRef, long snapshotVersion) {

        OverpaymentStrategy strategy = properties.getOverpaymentStrategy();
        BigDecimal excess = requestedAmount.subtract(totalDebt);

        log.warn("Overpayment detected creditAccountId={} requested={} totalDebt={} excess={} strategy={}",
                creditAccountId, requestedAmount, totalDebt, excess, strategy);

        if (strategy == OverpaymentStrategy.RETURN_TO_PAYER) {
            // Apply only totalDebt; return excess immediately
            PaymentOrder order = PaymentOrder.create(creditAccountId, obligorPartyId, totalDebt,
                    method, externalRef, snapshotVersion);
            order.recordOverpayment(requestedAmount, strategy);
            orderRepository.save(order);

            eventPublisher.publishPaymentApplied(order.getPaymentOrderId().toString(),
                    creditAccountId, totalDebt, snapshotVersion);

            // Publish excess return — keyed with "-excess" suffix for correlation
            eventPublisher.publishPaymentReversed(order.getPaymentOrderId() + "-excess",
                    creditAccountId, excess, "OVERPAYMENT_RETURN_TO_PAYER");

            log.info("Overpayment RETURN_TO_PAYER orderId={} applied={} returned={}",
                    order.getPaymentOrderId(), totalDebt, excess);
            return order;
        }

        // APPLY_NEXT_INSTALLMENT: forward full amount to credit-portfolio
        PaymentOrder order = PaymentOrder.create(creditAccountId, obligorPartyId, requestedAmount,
                method, externalRef, snapshotVersion);
        order.recordOverpayment(requestedAmount, strategy);   // same as amount; marks strategy
        orderRepository.save(order);

        eventPublisher.publishPaymentApplied(order.getPaymentOrderId().toString(),
                creditAccountId, requestedAmount, snapshotVersion);

        log.info("Overpayment APPLY_NEXT_INSTALLMENT orderId={} amount={}",
                order.getPaymentOrderId(), requestedAmount);
        return order;
    }

    /**
     * Called when credit-portfolio publishes balance-updated after PAYMENT_APPLIED.
     * Transitions the matching PENDING order to CONFIRMED.
     */
    public void confirmByEventId(String sourceEventId) {
        orderRepository.findById(UUID.fromString(sourceEventId)).ifPresent(order -> {
            if (order.isPending()) {
                order.confirm();
                orderRepository.save(order);
                log.info("PaymentOrder CONFIRMED orderId={}", sourceEventId);
            }
        });
    }

    /**
     * Called when credit-portfolio publishes payment-rejected.
     * Transitions the matching PENDING order to REJECTED.
     */
    public void rejectByEventId(String sourceEventId, String reason) {
        orderRepository.findById(UUID.fromString(sourceEventId)).ifPresent(order -> {
            if (order.isPending()) {
                order.reject(reason);
                orderRepository.save(order);
                log.warn("PaymentOrder REJECTED orderId={} reason={}", sourceEventId, reason);
            }
        });
    }

    /**
     * Reverse a CONFIRMED payment (e.g., SPEI devolution).
     *
     * Guards:
     *   PY-07  payment method must support reversal (VENTANILLA does not)
     *   PY-08  reversal window: reversalWindowHours after confirmedAt
     */
    public PaymentOrder reverse(UUID paymentOrderId, String reason) {
        PaymentOrder order = orderRepository.findById(paymentOrderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException(paymentOrderId.toString()));

        // PY-07: method must allow reversal
        PaymentMethod method = PaymentMethod.valueOf(order.getPaymentMethod());
        if (!method.allowsReversal()) {
            throw new InvalidPaymentStateException(
                    "Payment method " + method + " does not support programmatic reversal");
        }

        // PY-08: reversal window
        if (order.getConfirmedAt() != null) {
            Instant windowEnd = order.getConfirmedAt()
                    .plus(Duration.ofHours(properties.getReversalWindowHours()));
            if (Instant.now().isAfter(windowEnd)) {
                throw new InvalidPaymentStateException(
                        "Reversal window expired. Payments can only be reversed within "
                        + properties.getReversalWindowHours() + " hours of confirmation.");
            }
        }

        order.reverse(reason);
        orderRepository.save(order);

        eventPublisher.publishPaymentReversed(order.getPaymentOrderId().toString(),
                order.getCreditAccountId(), order.getAmount(), reason);

        log.info("PaymentOrder REVERSED orderId={} creditAccountId={} reason={}",
                paymentOrderId, order.getCreditAccountId(), reason);
        return order;
    }

    @Transactional(readOnly = true)
    public PaymentOrder findById(UUID paymentOrderId) {
        return orderRepository.findById(paymentOrderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException(paymentOrderId.toString()));
    }

    @Transactional(readOnly = true)
    public List<PaymentOrder> findByCreditAccountId(UUID creditAccountId) {
        return orderRepository.findAllByCreditAccountIdOrderByCreatedAtDesc(creditAccountId);
    }
}
