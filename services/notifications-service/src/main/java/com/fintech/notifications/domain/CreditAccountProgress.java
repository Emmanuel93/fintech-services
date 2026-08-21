package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Soporta #5 (cuota pagada, en alcance v1) y los hitos derivados del backlog (#27/#28). Es una
 * <strong>aproximación local, no un dato exacto</strong>: {@code credit-portfolio.InstallmentStatus}
 * define PAID/PARTIAL en su enum pero ningún código del sistema los asigna — los pagos se aplican
 * contra el saldo agregado de la cuenta, no contra una cuota específica (hallazgo verificado en
 * código, 2026-07-16). {@link #tryMarkInstallmentPaid} compara el monto del pago contra la última
 * cuota vigente conocida vía {@code collections.pre-due-reminder-triggered} — falla en pagos
 * parciales o que cubren varias cuotas de golpe (NT-11, documentado como best-effort).
 */
@Entity
@Table(name = "credit_account_progress", schema = "notifications")
public class CreditAccountProgress {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    /** De CreditAccountActivated — usado para el copy de LOAN_SETTLED (balance-updated no lo trae). */
    @Column(name = "product_type")
    private String productType;

    /** De ApplicationProspectLink.offeredTerm — null si esa correlación no se resolvió a tiempo. */
    @Column(name = "total_installments")
    private Integer totalInstallments;

    @Column(name = "installments_paid_count", nullable = false)
    private int installmentsPaidCount;

    @Column(name = "current_due_date")
    private LocalDate currentDueDate;

    @Column(name = "current_total_amount")
    private BigDecimal currentTotalAmount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreditAccountProgress() {}

    public static CreditAccountProgress init(UUID creditAccountId, UUID obligorPartyId, String productType,
                                              Integer totalInstallments) {
        CreditAccountProgress p = new CreditAccountProgress();
        p.creditAccountId = creditAccountId;
        p.obligorPartyId   = obligorPartyId;
        p.productType        = productType;
        p.totalInstallments   = totalInstallments;
        p.installmentsPaidCount = 0;
        p.updatedAt               = Instant.now();
        return p;
    }

    /** Llamado en cada {@code pre-due-reminder-triggered} — reemplaza la cuota vigente conocida. */
    public void updateCurrentInstallment(LocalDate dueDate, BigDecimal totalAmount) {
        this.currentDueDate    = dueDate;
        this.currentTotalAmount = totalAmount;
        this.updatedAt            = Instant.now();
    }

    /**
     * NT-11: si no hay cuota vigente cargada, o el pago no la cubre, no dispara nada (no es un
     * error). Si la cubre, avanza el contador, limpia la cuota vigente (consumida) y devuelve el
     * número de cuota "aproximado" (installmentsPaidCount tras el incremento) para el copy.
     */
    public Optional<Integer> tryMarkInstallmentPaid(BigDecimal paymentAmount) {
        if (currentTotalAmount == null || paymentAmount == null
                || paymentAmount.compareTo(currentTotalAmount) < 0) {
            return Optional.empty();
        }
        this.installmentsPaidCount++;
        this.currentDueDate     = null;
        this.currentTotalAmount = null;
        this.updatedAt            = Instant.now();
        return Optional.of(installmentsPaidCount);
    }

    /** null si totalInstallments no se resolvió — el copy debe omitir "cuotas restantes" en ese caso. */
    public Integer remainingInstallments() {
        return totalInstallments == null ? null : Math.max(totalInstallments - installmentsPaidCount, 0);
    }

    public UUID getCreditAccountId()        { return creditAccountId; }
    public UUID getObligorPartyId()          { return obligorPartyId; }
    public String getProductType()            { return productType; }
    public Integer getTotalInstallments()       { return totalInstallments; }
    public int getInstallmentsPaidCount()        { return installmentsPaidCount; }
    public LocalDate getCurrentDueDate()          { return currentDueDate; }
    public BigDecimal getCurrentTotalAmount()      { return currentTotalAmount; }
    public Instant getUpdatedAt()                   { return updatedAt; }
}
