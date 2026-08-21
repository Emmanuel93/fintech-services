package com.fintech.disbursement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** A quién se le paga. Vocabulario de pagos, no de crédito: aquí no hay "obligado" ni "party". */
@Embeddable
public class Beneficiary {

    @Column(name = "beneficiary_name", nullable = false, updatable = false)
    private String name;

    @Column(name = "beneficiary_account", nullable = false, updatable = false)
    private String account;

    /** Catálogo del rail: "40" CLABE · "3" tarjeta · "10" celular. */
    @Column(name = "beneficiary_account_type", nullable = false, updatable = false)
    private String accountType;

    @Column(name = "beneficiary_tax_id")
    private String taxId;

    @Column(name = "beneficiary_institution")
    private Integer institution;

    protected Beneficiary() {
    }

    public static Beneficiary of(String name, String account, String accountType,
                                 String taxId, Integer institution) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("El nombre del beneficiario es obligatorio");
        }
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("La cuenta del beneficiario es obligatoria");
        }
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.name = name;
        beneficiary.account = account;
        beneficiary.accountType = accountType != null ? accountType : "40";
        beneficiary.taxId = taxId;
        beneficiary.institution = institution;
        return beneficiary;
    }

    public boolean isClabe() { return "40".equals(accountType); }

    public String getName() { return name; }
    public String getAccount() { return account; }
    public String getAccountType() { return accountType; }
    public String getTaxId() { return taxId; }
    public Integer getInstitution() { return institution; }

    /** Nunca vuelca la cuenta completa: los logs de pagos son PII. */
    @Override
    public String toString() {
        return "Beneficiary{name=" + name + ", account=" + mask(account) + "}";
    }

    public static String mask(String account) {
        return account == null || account.length() < 4 ? "****" : "****" + account.substring(account.length() - 4);
    }
}
