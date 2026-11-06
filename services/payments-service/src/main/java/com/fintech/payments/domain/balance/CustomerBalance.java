package com.fintech.payments.domain.balance;

import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A payer's balance in favor (saldo a favor): money already in the bank that no loan owed when it arrived. A liability
 * to the payer, never negative. Optimistic locking: two applications at once cannot spend the same peso twice.
 */
@Entity
@Table(schema = "payments", name = "customer_balances")
public class CustomerBalance {

    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerBalance() {
    }

    public static CustomerBalance openFor(UUID partyId) {
        CustomerBalance b = new CustomerBalance();
        b.partyId = Objects.requireNonNull(partyId, "partyId");
        b.balance = BigDecimal.ZERO.setScale(2);
        b.updatedAt = BusinessClock.current().now();
        return b;
    }

    /** Applies a movement of {@code type} and returns the balance after it. */
    public BigDecimal move(BalanceMovementType type, BigDecimal amount) {
        BigDecimal value = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Un movimiento de saldo a favor necesita importe positivo: " + amount);
        }
        BigDecimal next = type.increases() ? balance.add(value) : balance.subtract(value);
        if (next.signum() < 0) {
            throw new IllegalStateException("El saldo a favor de " + partyId + " no alcanza: hay " + balance
                    + " y se piden " + value);
        }
        balance = next;
        updatedAt = BusinessClock.current().now();
        return balance;
    }

    public boolean hasBalance() {
        return balance.signum() > 0;
    }

    public UUID getPartyId() { return partyId; }
    public BigDecimal getBalance() { return balance; }
    public Instant getUpdatedAt() { return updatedAt; }
}
