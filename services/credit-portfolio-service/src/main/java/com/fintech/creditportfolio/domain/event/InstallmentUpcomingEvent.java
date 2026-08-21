package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Published N days before an installment's dueDate (T5 lead time) — feeds Collections' early collections. */
public class InstallmentUpcomingEvent {

    private final UUID installmentId;
    private final UUID creditAccountId;
    private final LocalDate dueDate;
    private final BigDecimal totalAmount;
    private final Instant occurredAt;

    public InstallmentUpcomingEvent(UUID installmentId, UUID creditAccountId,
                                     LocalDate dueDate, BigDecimal totalAmount) {
        this.installmentId   = installmentId;
        this.creditAccountId = creditAccountId;
        this.dueDate          = dueDate;
        this.totalAmount     = totalAmount;
        this.occurredAt      = Instant.now();
    }

    public UUID getInstallmentId()     { return installmentId; }
    public UUID getCreditAccountId()   { return creditAccountId; }
    public LocalDate getDueDate()      { return dueDate; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Instant getOccurredAt()     { return occurredAt; }
}
