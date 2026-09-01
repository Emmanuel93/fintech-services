package com.fintech.closing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Un corte de una cuenta: <b>propiedad del cierre</b>, derivado de la política del producto.
 *
 * <p>Una vez sellado es <b>inmutable</b>. Lo que se mueva después es «posterior al corte» y se
 * presenta aparte. Es como funciona un estado de cuenta real, y es lo que permite que el cliente
 * reciba un documento que no se contradice al día siguiente.
 */
@Entity
@Table(name = "cutoff_schedules", schema = "closing")
@IdClass(CutoffSchedule.Key.class)
public class CutoffSchedule {

    @Id @Column(name = "credit_account_id", nullable = false) private UUID creditAccountId;
    @Id @Column(name = "cycle_number", nullable = false)      private int cycleNumber;

    @Column(name = "cutoff_date", nullable = false)      private LocalDate cutoffDate;
    @Column(name = "payment_due_date", nullable = false)  private LocalDate paymentDueDate;
    @Column(name = "status", nullable = false, length = 12) private String status;

    @Column(name = "balance_at_cutoff", precision = 19, scale = 4)   private BigDecimal balanceAtCutoff;
    @Column(name = "principal_at_cutoff", precision = 19, scale = 4) private BigDecimal principalAtCutoff;
    @Column(name = "interest_at_cutoff", precision = 19, scale = 4)  private BigDecimal interestAtCutoff;
    @Column(name = "penalty_at_cutoff", precision = 19, scale = 4)   private BigDecimal penaltyAtCutoff;
    @Column(name = "amount_due", precision = 19, scale = 4)          private BigDecimal amountDue;
    @Column(name = "minimum_payment", precision = 19, scale = 4)     private BigDecimal minimumPayment;
    @Column(name = "movement_count")                                  private Integer movementCount;
    @Column(name = "sealed_at")                                       private Instant sealedAt;
    @Column(name = "propagated_at")                                   private Instant propagatedAt;

    protected CutoffSchedule() {}

    public static CutoffSchedule scheduled(UUID creditAccountId, int cycleNumber,
                                            LocalDate cutoffDate, LocalDate paymentDueDate) {
        CutoffSchedule c = new CutoffSchedule();
        c.creditAccountId = creditAccountId;
        c.cycleNumber     = cycleNumber;
        c.cutoffDate      = cutoffDate;
        c.paymentDueDate  = paymentDueDate;
        c.status          = "SCHEDULED";
        return c;
    }

    /**
     * Sella el corte con las cifras del día.
     *
     * @throws IllegalStateException si ya estaba sellado. Reabrir un corte publicado es lo que hace
     *         que el estado de cuenta del cliente cambie después de habérselo mandado.
     */
    public void seal(BigDecimal principal, BigDecimal interest, BigDecimal penalty,
                     BigDecimal amountDue, BigDecimal minimumPayment, int movementCount) {
        if (!"SCHEDULED".equals(status)) {
            throw new IllegalStateException(
                    "El corte " + cycleNumber + " de la cuenta " + creditAccountId
                    + " ya está " + status + ": un corte sellado no se reabre");
        }
        this.principalAtCutoff = principal;
        this.interestAtCutoff  = interest;
        this.penaltyAtCutoff   = penalty;
        this.balanceAtCutoff   = nz(principal).add(nz(interest)).add(nz(penalty));
        this.amountDue         = amountDue;
        this.minimumPayment    = minimumPayment;
        this.movementCount     = movementCount;
        this.status            = "SEALED";
        this.sealedAt          = Instant.now();
    }

    public void markPropagated() {
        this.status       = "PROPAGATED";
        this.propagatedAt = Instant.now();
    }

    public void markSkipped() { this.status = "SKIPPED"; }

    public boolean isSealed() { return "SEALED".equals(status) || "PROPAGATED".equals(status); }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    public UUID getCreditAccountId()   { return creditAccountId; }
    public int getCycleNumber()        { return cycleNumber; }
    public LocalDate getCutoffDate()   { return cutoffDate; }
    public LocalDate getPaymentDueDate() { return paymentDueDate; }
    public String getStatus()          { return status; }
    public BigDecimal getAmountDue()   { return amountDue; }
    public BigDecimal getMinimumPayment() { return minimumPayment; }
    public BigDecimal getBalanceAtCutoff() { return balanceAtCutoff; }
    public Integer getMovementCount()  { return movementCount; }

    public static class Key implements Serializable {
        private UUID creditAccountId;
        private int cycleNumber;
        public Key() {}
        public Key(UUID creditAccountId, int cycleNumber) {
            this.creditAccountId = creditAccountId; this.cycleNumber = cycleNumber;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return cycleNumber == k.cycleNumber && Objects.equals(creditAccountId, k.creditAccountId);
        }
        @Override public int hashCode() { return Objects.hash(creditAccountId, cycleNumber); }
    }
}
