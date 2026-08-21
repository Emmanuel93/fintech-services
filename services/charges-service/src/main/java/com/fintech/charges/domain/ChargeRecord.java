package com.fintech.charges.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "charges", name = "charge_records")
public class ChargeRecord {

    @Id
    @Column(name = "charge_id")
    private UUID chargeId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "charge_type", nullable = false, updatable = false)
    private String chargeType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "basis", precision = 20, scale = 4)
    private BigDecimal basis;

    @Column(name = "rate", precision = 12, scale = 8)
    private BigDecimal rate;

    @Column(name = "days")
    private Integer days;

    @Column(name = "amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(name = "tax_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "accrual_date", nullable = false, updatable = false)
    private LocalDate accrualDate;

    @Column(name = "linked_charge_id")
    private UUID linkedChargeId;

    @Column(name = "reversal_reason")
    private String reversalReason;

    @Column(name = "waived_by")
    private String waivedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    protected ChargeRecord() {}

    public static ChargeRecord create(UUID creditAccountId, UUID obligorPartyId,
                                       ChargeType chargeType, BigDecimal basis, BigDecimal rate,
                                       Integer days, BigDecimal amount, BigDecimal taxAmount,
                                       LocalDate accrualDate, UUID linkedChargeId) {
        var r = new ChargeRecord();
        r.chargeId        = UUID.randomUUID();
        r.creditAccountId = creditAccountId;
        r.obligorPartyId  = obligorPartyId;
        r.chargeType      = chargeType.name();
        r.status          = ChargeStatus.APPLIED.name();
        r.basis           = basis;
        r.rate            = rate;
        r.days            = days;
        r.amount          = amount;
        r.taxAmount       = taxAmount != null ? taxAmount : BigDecimal.ZERO;
        r.totalAmount     = amount.add(r.taxAmount);
        r.accrualDate     = accrualDate;
        r.linkedChargeId  = linkedChargeId;
        r.createdAt       = Instant.now();
        return r;
    }

    public static ChargeRecord createIva(UUID creditAccountId, UUID obligorPartyId,
                                          BigDecimal taxAmount, LocalDate accrualDate,
                                          UUID linkedChargeId) {
        return create(creditAccountId, obligorPartyId, ChargeType.IVA,
                null, null, null, taxAmount, BigDecimal.ZERO, accrualDate, linkedChargeId);
    }

    public void reverse(String reason) {
        if (!ChargeStatus.APPLIED.name().equals(status)) {
            throw new InvalidChargeStateException(
                    "ChargeRecord " + chargeId + " status=" + status + " — cannot reverse");
        }
        this.status        = ChargeStatus.REVERSED.name();
        this.reversalReason = reason;
        this.reversedAt    = Instant.now();
    }

    public void waive(String waivedByUser) {
        if (!ChargeStatus.APPLIED.name().equals(status)) {
            throw new InvalidChargeStateException(
                    "ChargeRecord " + chargeId + " status=" + status + " — cannot waive");
        }
        this.status    = ChargeStatus.WAIVED.name();
        this.waivedBy  = waivedByUser;
        this.reversedAt = Instant.now();
    }

    public boolean isApplied() { return ChargeStatus.APPLIED.name().equals(status); }

    public UUID getChargeId()          { return chargeId; }
    public UUID getCreditAccountId()   { return creditAccountId; }
    public UUID getObligorPartyId()    { return obligorPartyId; }
    public String getChargeType()      { return chargeType; }
    public String getStatus()          { return status; }
    public BigDecimal getBasis()       { return basis; }
    public BigDecimal getRate()        { return rate; }
    public Integer getDays()           { return days; }
    public BigDecimal getAmount()      { return amount; }
    public BigDecimal getTaxAmount()   { return taxAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public LocalDate getAccrualDate()  { return accrualDate; }
    public UUID getLinkedChargeId()    { return linkedChargeId; }
    public String getReversalReason()  { return reversalReason; }
    public String getWaivedBy()        { return waivedBy; }
    public Instant getCreatedAt()      { return createdAt; }
    public Instant getReversedAt()     { return reversedAt; }
}
