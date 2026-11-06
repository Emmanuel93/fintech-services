package com.fintech.commission.domain;

import com.fintech.shared.banking.ClabeCheckDigit;
import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Who a commission is paid to, as commission needs it: the fiscal profile party publishes and the account the lot's
 * SPEI goes to.
 *
 * <p>The RFC decides whether the beneficiary is a natural person (13 characters; a company's has 12): a company that
 * pays a natural person's commission withholds ISR and two thirds of the IVA. The regime decides the ISR rate: the
 * simplified trust regime (RESICO, 626) withholds 1.25 %, the others 10 %.
 */
@Entity
@Table(name = "beneficiary_profiles", schema = "commission")
public class BeneficiaryProfile {

    /** Simplified trust regime (Régimen Simplificado de Confianza). */
    public static final String RESICO = "626";

    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "rfc", length = 13)
    private String rfc;

    @Column(name = "party_type", length = 20)
    private String partyType;

    @Column(name = "tax_name", length = 300)
    private String taxName;

    @Column(name = "tax_regime", length = 5)
    private String taxRegime;

    @Column(name = "payout_clabe", length = 18)
    private String payoutClabe;

    @Column(name = "payout_holder", length = 120)
    private String payoutHolder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BeneficiaryProfile() {}

    public static BeneficiaryProfile of(UUID partyId) {
        BeneficiaryProfile p = new BeneficiaryProfile();
        p.partyId   = partyId;
        p.updatedAt = BusinessClock.current().now();
        return p;
    }

    /** The fiscal profile as party published it. */
    public void updateFiscal(String rfc, String partyType, String taxName, String taxRegime) {
        this.rfc       = rfc != null ? rfc.trim().toUpperCase() : null;
        this.partyType = partyType;
        this.taxName   = taxName;
        this.taxRegime = taxRegime;
        this.updatedAt = BusinessClock.current().now();
    }

    /**
     * The account the lots are paid to: an 18-digit CLABE whose check digit is right. One that is not would be refused
     * by the rail when the lot is paid, long after whoever typed it.
     */
    public void updatePayoutAccount(String clabe, String holder) {
        String digits = clabe == null ? "" : clabe.trim();
        if (!ClabeCheckDigit.isValid(digits)) {
            throw new IllegalArgumentException("La CLABE de pago no es válida (18 dígitos con su dígito de control): " + clabe);
        }
        this.payoutClabe  = digits;
        this.payoutHolder = holder != null && !holder.isBlank() ? holder.trim() : taxName;
        this.updatedAt    = BusinessClock.current().now();
    }

    /** A natural person: by the RFC's length when there is one, by the party type otherwise. */
    public boolean isNaturalPerson() {
        if (rfc != null && !rfc.isBlank()) return rfc.length() == 13;
        return partyType != null && !"BUSINESS".equalsIgnoreCase(partyType) && !"COMPANY".equalsIgnoreCase(partyType);
    }

    public boolean isResico() { return RESICO.equals(taxRegime); }

    /**
     * A lot can be opened and paid: the RFC says what to withhold (and who invoices it) and the CLABE where the SPEI
     * goes. Without either, the commissions stay accrued and carry over to the next run.
     */
    public boolean isPayable() { return rfc != null && !rfc.isBlank() && payoutClabe != null; }

    /** Why a lot cannot be paid yet, in the words an operator reads; null when it can. */
    public String whyNotPayable() {
        if (rfc == null || rfc.isBlank()) return "sin perfil fiscal (RFC)";
        if (payoutClabe == null) return "sin cuenta de pago (CLABE)";
        return null;
    }

    public UUID getPartyId()        { return partyId; }
    public String getRfc()          { return rfc; }
    public String getPartyType()    { return partyType; }
    public String getTaxName()      { return taxName; }
    public String getTaxRegime()    { return taxRegime; }
    public String getPayoutClabe()  { return payoutClabe; }
    public String getPayoutHolder() { return payoutHolder; }
    public Instant getUpdatedAt()   { return updatedAt; }
}
