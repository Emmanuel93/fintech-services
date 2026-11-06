package com.fintech.payments.domain.balance;

import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One change of a payer's balance in favor, with what caused it: the received credit, the payment order, the loan.
 * Idempotent by its source: the same cause seen twice (a redelivered event) is one movement.
 */
@Entity
@Table(schema = "payments", name = "customer_balance_movements")
public class CustomerBalanceMovement {

    @Id
    @Column(name = "movement_id", nullable = false, updatable = false)
    private UUID movementId;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "movement_type", nullable = false, updatable = false)
    private String movementType;

    /** Signed: positive raises the balance, negative lowers it. */
    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "source_key", nullable = false, updatable = false)
    private String sourceKey;

    @Column(name = "inbound_credit_id", updatable = false)
    private UUID inboundCreditId;

    @Column(name = "payment_order_id", updatable = false)
    private UUID paymentOrderId;

    @Column(name = "credit_account_id", updatable = false)
    private UUID creditAccountId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "note", updatable = false)
    private String note;

    protected CustomerBalanceMovement() {
    }

    public static CustomerBalanceMovement record(UUID partyId, BalanceMovementType type, BigDecimal amount,
                                                 BigDecimal balanceAfter, String sourceKey, UUID inboundCreditId,
                                                 UUID paymentOrderId, UUID creditAccountId, String note) {
        CustomerBalanceMovement m = new CustomerBalanceMovement();
        m.movementId = UUID.randomUUID();
        m.partyId = partyId;
        m.movementType = type.name();
        m.amount = type.increases() ? amount : amount.negate();
        m.balanceAfter = balanceAfter;
        m.sourceKey = sourceKey;
        m.inboundCreditId = inboundCreditId;
        m.paymentOrderId = paymentOrderId;
        m.creditAccountId = creditAccountId;
        m.occurredAt = BusinessClock.current().now();
        m.businessDate = BusinessClock.current().today();
        m.note = note;
        return m;
    }

    public UUID getMovementId() { return movementId; }
    public UUID getPartyId() { return partyId; }
    public BalanceMovementType type() { return BalanceMovementType.valueOf(movementType); }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getSourceKey() { return sourceKey; }
    public UUID getInboundCreditId() { return inboundCreditId; }
    public UUID getPaymentOrderId() { return paymentOrderId; }
    public UUID getCreditAccountId() { return creditAccountId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getNote() { return note; }
}
