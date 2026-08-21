package com.fintech.stp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Cuenta ordenante de una empresa: de dónde sale el dinero.
 *
 * <p>El legado la resolvía con {@code findById(1)} o con una CLABE literal en el código.
 */
@Entity
@Table(schema = "stp", name = "ordering_accounts")
public class OrderingAccount {

    @Id
    @Column(name = "ordering_account_id", nullable = false, updatable = false)
    private UUID orderingAccountId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "clabe", nullable = false, updatable = false)
    private String clabe;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(name = "tax_id")
    private String taxId;

    /** Tipo de cuenta según el catálogo de STP: "40" CLABE, "3" tarjeta, "10" celular. */
    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "stp_client_number")
    private String stpClientNumber;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAccount;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderingAccount() {
    }

    public static OrderingAccount create(UUID companyId, String clabe, String holderName, String taxId,
                                         String accountType, String stpClientNumber, boolean defaultAccount) {
        if (!ClabeValidator.isValid(clabe)) {
            throw new IllegalArgumentException("La CLABE ordenante no es válida: dígito verificador incorrecto");
        }
        OrderingAccount account = new OrderingAccount();
        account.orderingAccountId = UUID.randomUUID();
        account.companyId = companyId;
        account.clabe = clabe;
        account.holderName = holderName;
        account.taxId = taxId;
        account.accountType = accountType != null ? accountType : "40";
        account.currency = "MXN";
        account.stpClientNumber = stpClientNumber;
        account.defaultAccount = defaultAccount;
        account.active = true;
        account.createdAt = Instant.now();
        return account;
    }

    public void deactivate() { this.active = false; }

    /** Deja de ser la cuenta por default. Cambiar de cuenta ordenante es una operación normal. */
    public void clearDefault() { this.defaultAccount = false; }

    /** El tipo de cuenta que espera la cadena original es numérico. */
    public Integer accountTypeAsInt() { return Integer.valueOf(accountType); }

    public UUID getOrderingAccountId() { return orderingAccountId; }
    public UUID getCompanyId() { return companyId; }
    public String getClabe() { return clabe; }
    public String getHolderName() { return holderName; }
    public String getTaxId() { return taxId; }
    public String getAccountType() { return accountType; }
    public String getCurrency() { return currency; }
    public String getStpClientNumber() { return stpClientNumber; }
    public boolean isDefaultAccount() { return defaultAccount; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
