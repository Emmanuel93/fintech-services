package com.fintech.accounting.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "ledger_accounts", schema = "accounting")
public class LedgerAccount {

    @Id
    @Column(nullable = false, updatable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AccountType type;

    protected LedgerAccount() {}

    public String getCode()      { return code; }
    public String getName()      { return name; }
    public AccountType getType() { return type; }
}
