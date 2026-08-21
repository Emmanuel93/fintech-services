package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class InstallmentDueEvent {

    private final UUID installmentId;
    private final UUID creditAccountId;
    private final int installmentNumber;
    private final LocalDate dueDate;
    private final BigDecimal totalAmount;
    private final Instant occurredAt;

    public InstallmentDueEvent(UUID installmentId, UUID creditAccountId,
                                int installmentNumber, LocalDate dueDate,
                                BigDecimal totalAmount) {
        this.installmentId     = installmentId;
        this.creditAccountId   = creditAccountId;
        this.installmentNumber = installmentNumber;
        this.dueDate           = dueDate;
        this.totalAmount       = totalAmount;
        this.occurredAt        = Instant.now();
    }

    public UUID getInstallmentId()     { return installmentId; }
    public UUID getCreditAccountId()   { return creditAccountId; }
    public int getInstallmentNumber()  { return installmentNumber; }
    public LocalDate getDueDate()      { return dueDate; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Instant getOccurredAt()     { return occurredAt; }
}
