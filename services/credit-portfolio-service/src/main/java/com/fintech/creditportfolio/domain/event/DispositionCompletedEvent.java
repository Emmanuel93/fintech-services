package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A wallet-initiated disposition finished processing. dispositionType decides how the
 * consumer should react — Wallet credits its own walletBalance only for SELF_USE
 * (money stayed on the platform); THIRD_PARTY_CREDIT/PAYROLL left via external SPEI.
 */
public class DispositionCompletedEvent {

    private final UUID dispositionId;
    private final UUID creditAccountId;
    private final UUID obligorPartyId;
    private final BigDecimal amount;
    private final String dispositionType;
    private final BigDecimal availableCredit;
    private final long balanceVersion;
    private final Instant occurredAt;

    public DispositionCompletedEvent(UUID dispositionId, UUID creditAccountId, UUID obligorPartyId,
                                      BigDecimal amount, String dispositionType,
                                      BigDecimal availableCredit, long balanceVersion) {
        this.dispositionId   = dispositionId;
        this.creditAccountId = creditAccountId;
        this.obligorPartyId  = obligorPartyId;
        this.amount          = amount;
        this.dispositionType = dispositionType;
        this.availableCredit = availableCredit;
        this.balanceVersion  = balanceVersion;
        this.occurredAt      = Instant.now();
    }

    public UUID getDispositionId()       { return dispositionId; }
    public UUID getCreditAccountId()     { return creditAccountId; }
    public UUID getObligorPartyId()      { return obligorPartyId; }
    public BigDecimal getAmount()        { return amount; }
    public String getDispositionType()   { return dispositionType; }
    public BigDecimal getAvailableCredit() { return availableCredit; }
    public long getBalanceVersion()      { return balanceVersion; }
    public Instant getOccurredAt()       { return occurredAt; }
}
