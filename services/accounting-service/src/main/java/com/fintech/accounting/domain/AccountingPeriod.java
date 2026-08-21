package com.fintech.accounting.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un mes contable y si acepta asientos.
 *
 * <p>La tabla existía desde el primer changeset y <b>ninguna línea la consultaba</b>: se podía
 * asentar en un período cerrado sin resistencia, que es la forma clásica de que unos estados
 * financieros publicados dejen de coincidir con la base que los produjo.
 */
@Entity
@Table(name = "accounting_periods", schema = "accounting")
public class AccountingPeriod {

    @Id
    @Column(nullable = false, updatable = false, length = 6)
    private String period;   // YYYYMM

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PeriodStatus status;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected AccountingPeriod() {}

    public static AccountingPeriod open(String period) {
        AccountingPeriod p = new AccountingPeriod();
        p.period = period;
        p.status = PeriodStatus.OPEN;
        return p;
    }

    public void close() {
        this.status   = PeriodStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    public void reopen() {
        this.status   = PeriodStatus.OPEN;
        this.closedAt = null;
    }

    public boolean isOpen()          { return status == PeriodStatus.OPEN; }
    public String getPeriod()        { return period; }
    public PeriodStatus getStatus()  { return status; }
    public Instant getClosedAt()     { return closedAt; }
}
