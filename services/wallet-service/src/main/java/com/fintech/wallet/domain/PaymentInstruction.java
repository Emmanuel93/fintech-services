package com.fintech.wallet.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Instrucción de pago creada por el cliente. Ciclo: PENDING → SENT | CANCELLED | EXPIRED.
 * Invariante PI-01: once SENT the record is immutable.
 */
@Entity
@Table(schema = "wallet", name = "payment_instructions")
public class PaymentInstruction {

    @Id
    @Column(name = "instruction_id")
    private UUID instructionId;

    @Column(name = "credit_account_id", nullable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false)
    private UUID obligorPartyId;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "payment_type", nullable = false, length = 15)
    private String paymentType;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "payment_ref", length = 255)
    private String paymentRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentInstruction() {}

    public static PaymentInstruction create(UUID creditAccountId,
                                             UUID obligorPartyId,
                                             PaymentMethod method,
                                             BigDecimal amount,
                                             PaymentType type,
                                             Instant scheduledAt,
                                             Instant expiresAt,
                                             String paymentRef) {
        var pi = new PaymentInstruction();
        pi.instructionId   = UUID.randomUUID();
        pi.creditAccountId = creditAccountId;
        pi.obligorPartyId  = obligorPartyId;
        pi.paymentMethod   = method.name();
        pi.amount          = amount;
        pi.paymentType     = type.name();
        pi.scheduledAt     = scheduledAt;
        pi.status          = "PENDING";
        pi.expiresAt       = expiresAt;
        pi.paymentRef      = paymentRef;
        pi.createdAt       = Instant.now();
        pi.updatedAt       = pi.createdAt;
        return pi;
    }

    public void markSent() {
        requirePending();
        this.status    = "SENT";
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        requirePending();
        this.status    = "CANCELLED";
        this.updatedAt = Instant.now();
    }

    public void expire() {
        requirePending();
        this.status    = "EXPIRED";
        this.updatedAt = Instant.now();
    }

    private void requirePending() {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("PaymentInstruction " + instructionId + " is already " + status);
        }
    }

    public boolean isPending()   { return "PENDING".equals(status); }
    public boolean isSent()      { return "SENT".equals(status); }

    public UUID getInstructionId()      { return instructionId; }
    public UUID getCreditAccountId()    { return creditAccountId; }
    public UUID getObligorPartyId()     { return obligorPartyId; }
    public String getPaymentMethod()    { return paymentMethod; }
    public BigDecimal getAmount()       { return amount; }
    public String getPaymentType()      { return paymentType; }
    public Instant getScheduledAt()     { return scheduledAt; }
    public String getStatus()           { return status; }
    public Instant getExpiresAt()       { return expiresAt; }
    public String getPaymentRef()       { return paymentRef; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getUpdatedAt()       { return updatedAt; }
}
