package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Una cuenta <b>nuestra</b> en una institución financiera.
 *
 * <p>Antes vivía dentro del conector de STP como {@code stp.ordering_accounts}, y ahí la elección de
 * «por cuál sale» era un {@code is_default} por empresa: ciega al saldo, al costo y al horario. Es
 * una decisión de tesorería, y mientras vivió dentro del conector cada proveedor nuevo habría
 * traído su propia copia del catálogo.
 *
 * <p>Cada cuenta declara <b>tres</b> cuentas del mayor, y las tres son necesarias para que el cierre
 * bancario cuadre:
 * <ul>
 *   <li>{@code ledgerAccount} — contra la que se concilia el saldo (hoy {@code 1101}).</li>
 *   <li>{@code suspenseCreditAccount} — abonos que llegaron sin dueño identificable ({@code 2109}).</li>
 *   <li>{@code suspenseDebitAccount} — cargos que el banco hizo y no se han aclarado ({@code 1109}).</li>
 * </ul>
 *
 * <p>La ecuación del sello es {@code saldo contable + partidas en conciliación == saldo del banco}:
 * sin las dos puentes, lo no identificado no tiene dónde caer y la diferencia queda sin explicar.
 */
@Entity
@Table(name = "bank_accounts", schema = "banking")
public class BankAccount {

    @Id
    @Column(name = "bank_account_id", nullable = false)
    private UUID id;

    /** Nulo = cuenta de la operación, no de una empresa concreta. */
    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "institution_code", nullable = false, length = 10)
    private String institutionCode;

    @Column(name = "institution_name", nullable = false, length = 120)
    private String institutionName;

    @Column(name = "clabe", nullable = false, length = 18)
    private String clabe;

    @Column(name = "holder_name", nullable = false, length = 150)
    private String holderName;

    @Column(name = "tax_id", length = 18)
    private String taxId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "ledger_account", nullable = false, length = 10)
    private String ledgerAccount;

    @Column(name = "suspense_credit_account", nullable = false, length = 10)
    private String suspenseCreditAccount;

    @Column(name = "suspense_debit_account", nullable = false, length = 10)
    private String suspenseDebitAccount;

    /** Identificador ante el proveedor. Nulo mientras la cuenta no opere por ese rail. */
    @Column(name = "provider_client_ref", length = 40)
    private String providerClientRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private BankAccountStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected BankAccount() {}

    public static BankAccount alta(UUID companyId,
                                   String institutionCode,
                                   String institutionName,
                                   Clabe clabe,
                                   String holderName,
                                   String taxId,
                                   String currency,
                                   String ledgerAccount,
                                   String suspenseCreditAccount,
                                   String suspenseDebitAccount,
                                   String providerClientRef) {
        BankAccount c = new BankAccount();
        c.id                    = UUID.randomUUID();
        c.companyId             = companyId;
        c.institutionCode       = exigir(institutionCode, "institución");
        c.institutionName       = exigir(institutionName, "nombre de la institución");
        c.clabe                 = clabe.valor();
        c.holderName            = exigir(holderName, "titular");
        c.taxId                 = taxId;
        c.currency              = currency == null ? "MXN" : currency;
        c.ledgerAccount         = exigir(ledgerAccount, "cuenta contable");
        c.suspenseCreditAccount = exigir(suspenseCreditAccount, "puente de abonos");
        c.suspenseDebitAccount  = exigir(suspenseDebitAccount, "puente de cargos");
        c.providerClientRef     = providerClientRef;
        c.status                = BankAccountStatus.ACTIVE;
        c.createdAt             = OffsetDateTime.now();
        return c;
    }

    private static String exigir(String v, String campo) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Falta " + campo + " en la cuenta bancaria");
        }
        return v;
    }

    /**
     * Sacar la cuenta del ruteo sin perder su historia. Una cuenta cerrada no vuelve: cerrarla y
     * reabrirla sería otra cuenta, y la conciliación de lo que salió por ella dejaría de tener
     * sentido.
     */
    public void suspender() {
        if (status == BankAccountStatus.CLOSED) {
            throw new IllegalStateException("Una cuenta CLOSED no se suspende: ya no opera");
        }
        this.status = BankAccountStatus.SUSPENDED;
    }

    public void reactivar() {
        if (status == BankAccountStatus.CLOSED) {
            throw new IllegalStateException("Una cuenta CLOSED no se reactiva");
        }
        this.status = BankAccountStatus.ACTIVE;
    }

    public void cerrar() { this.status = BankAccountStatus.CLOSED; }

    public boolean puedeOperar() { return status.puedeOperar(); }

    public UUID getId()                       { return id; }
    public UUID getCompanyId()                { return companyId; }
    public String getInstitutionCode()        { return institutionCode; }
    public String getInstitutionName()        { return institutionName; }
    public String getClabe()                  { return clabe; }
    public String getClabeEnmascarada()       { return "****" + clabe.substring(14); }
    public String getHolderName()             { return holderName; }
    public String getTaxId()                  { return taxId; }
    public String getCurrency()               { return currency; }
    public String getLedgerAccount()          { return ledgerAccount; }
    public String getSuspenseCreditAccount()  { return suspenseCreditAccount; }
    public String getSuspenseDebitAccount()   { return suspenseDebitAccount; }
    public String getProviderClientRef()      { return providerClientRef; }
    public BankAccountStatus getStatus()      { return status; }
    public OffsetDateTime getCreatedAt()      { return createdAt; }
}
