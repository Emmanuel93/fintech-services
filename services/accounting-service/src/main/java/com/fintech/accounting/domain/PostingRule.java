package com.fintech.accounting.domain;

import jakarta.persistence.*;

/** triggerEvent → (cuenta cargo, cuenta abono). Catálogo configurable (T5). */
@Entity
@Table(name = "posting_rules", schema = "accounting")
public class PostingRule {

    @Id
    @Column(name = "trigger_event", nullable = false, updatable = false, length = 60)
    private String triggerEvent;

    @Column(name = "debit_account", nullable = false, length = 10)
    private String debitAccount;

    @Column(name = "credit_account", nullable = false, length = 10)
    private String creditAccount;

    @Column(length = 160)
    private String description;

    protected PostingRule() {}

    public String getTriggerEvent() { return triggerEvent; }
    public String getDebitAccount() { return debitAccount; }
    public String getCreditAccount(){ return creditAccount; }
    public String getDescription()  { return description; }
}
