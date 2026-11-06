package com.fintech.banking.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.banking.application.service.InternalMovementProjector;
import com.fintech.banking.domain.InternalMovement;
import com.fintech.shared.time.BusinessClock;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The money that moves without an STP order of the platform: the clients' payments (in) and the payments returned to
 * their banks or rejected by the portfolio and sent back (out). With the STP orders (dispositions, and the lots of
 * commissions paid to distributors and promoters, which go out by the rail like any order) the cash book holds every
 * movement of the account, and accounting can reconcile 1101 against it.
 */
@Component
public class TreasuryCashListener {

    private static final ZoneId BANXICO = ZoneId.of("America/Mexico_City");

    private final InternalMovementProjector cashBook;

    public TreasuryCashListener(InternalMovementProjector cashBook) {
        this.cashBook = cashBook;
    }

    /** {@code fundingSource}: DIRECT, SPEI_CREDIT or CREDIT_BALANCE (null in producers before it existed: DIRECT). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentAppliedPayload(String eventId, UUID creditAccountId, BigDecimal amount, LocalDate valueDate,
                                        String fundingSource) {}

    /** A credit received on a collection CLABE ({@code stp.credit-received}): the bank's money, with both dates. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreditReceivedPayload(UUID inboundCreditId, String trackingKey, LocalDate operationDate,
                                        Instant receivedAt, BigDecimal amount, String decision) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentReturnedPayload(String eventId, UUID creditAccountId, BigDecimal amount, LocalDate valueDate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentRejectedPayload(String sourceEventId, UUID creditAccountId, BigDecimal rejectedAmount,
                                         Instant occurredOn) {}

    @KafkaListener(topics = "payments.payment-applied", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentAppliedListenerContainerFactory")
    public void onPaymentApplied(PaymentAppliedPayload p) {
        if (p.eventId() == null || p.amount() == null || p.amount().signum() <= 0) return;
        // Only a direct payment brings its own money. A received credit's money entered with the credit itself
        // (stp.credit-received); a balance in favor was already in the bank.
        if (p.fundingSource() != null && !"DIRECT".equals(p.fundingSource())) return;
        cashBook.project(InternalMovement.from("CLIENT_PAYMENT", "PAGO:" + p.creditAccountId(), p.amount(),
                day(p.valueDate()), null, "CREDIT", "PAYMENT:" + p.eventId()));
    }

    /**
     * The money the bank says arrived on a collection CLABE, on its SPEI operation date, with the portfolio's date
     * beside it. A returned credit comes in and goes out the same operation day, as the bank statement shows it; it
     * never reached a loan.
     */
    @KafkaListener(topics = "stp.credit-received", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditReceivedListenerContainerFactory")
    public void onCreditReceived(CreditReceivedPayload c) {
        if (c.inboundCreditId() == null || c.amount() == null || c.amount().signum() <= 0 || c.operationDate() == null) {
            return;
        }
        LocalDate portfolioDate = c.receivedAt() != null ? c.receivedAt().atZone(BANXICO).toLocalDate() : c.operationDate();
        cashBook.project(InternalMovement.from("CLIENT_PAYMENT", "ABONO:" + c.trackingKey(), c.amount(),
                c.operationDate(), c.trackingKey(), "CREDIT", "CREDIT:" + c.inboundCreditId())
                .withPortfolioDate(portfolioDate));
        if ("RETURNED".equals(c.decision())) {
            cashBook.project(InternalMovement.from("PAYMENT_RETURN", "ABONO-DEVUELTO:" + c.trackingKey(), c.amount(),
                    c.operationDate(), c.trackingKey(), "DEBIT", "CREDIT-RETURNED:" + c.inboundCreditId())
                    .withPortfolioDate(portfolioDate));
        }
    }

    @KafkaListener(topics = "payments.payment-returned", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentReturnedListenerContainerFactory")
    public void onPaymentReturned(PaymentReturnedPayload p) {
        if (p.eventId() == null || p.amount() == null || p.amount().signum() == 0) return;
        cashBook.project(InternalMovement.from("PAYMENT_RETURN", "DEVOLUCION:" + p.creditAccountId(), p.amount().abs(),
                day(p.valueDate()), null, "DEBIT", "PAYMENT-RETURN:" + p.eventId()));
    }

    /**
     * A payment the portfolio did not take (the account could not receive it): the money did arrive, and it goes back
     * to the payer. It never reached the books, so in the cash book it comes in and goes out.
     */
    @KafkaListener(topics = "credit-portfolio.payment-rejected", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "paymentRejectedListenerContainerFactory")
    public void onPaymentRejected(PaymentRejectedPayload p) {
        if (p.sourceEventId() == null || p.rejectedAmount() == null || p.rejectedAmount().signum() <= 0) return;
        // Only money that came in with that payment goes back. A share of a received credit stays in the bank as the
        // payer's balance in favor (payments), so nothing leaves.
        if (!cashBook.brought("PAYMENT:" + p.sourceEventId())) return;
        cashBook.project(InternalMovement.from("PAYMENT_RETURN", "RECHAZO:" + p.creditAccountId(),
                p.rejectedAmount(), day(p.occurredOn()), null, "DEBIT", "PAYMENT-REJECTED:" + p.sourceEventId()));
    }

    private static LocalDate day(LocalDate valueDate) {
        return valueDate != null ? valueDate : BusinessClock.current().today();
    }

    private static LocalDate day(Instant occurredOn) {
        return occurredOn != null ? occurredOn.atZone(BANXICO).toLocalDate() : BusinessClock.current().today();
    }
}
