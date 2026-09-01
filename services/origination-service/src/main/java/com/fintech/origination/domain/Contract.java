package com.fintech.origination.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;

/**
 * Contract terms embedded in {@link CreditApplication} (Phase F — CM-04, CM-06).
 * Immutable once signed: {@link #complete} can only be called once.
 */
@Embeddable
public class Contract {

    @Column(name = "contract_number")
    private String contractNumber;

    /** e.g. ELECTRONIC | BIOMETRIC | HANDWRITTEN */
    @Column(name = "signature_method")
    private String signatureMethod;

    /** CM-06: CLABE validated before signing. */
    @Column(name = "clabe_account")
    private String clabeAccount;

    /** Reference to the signed PDF/document in document storage. */
    @Column(name = "document_ref")
    private String documentRef;

    @Column(name = "contract_signed_at")
    private Instant signedAt;

    /**
     * Días de BNPL que el cliente pidió al firmar, o {@code null} si no pidió ninguno.
     *
     * <p>BNPL es una <b>decisión del alta</b>: el cliente dice cuándo empieza a pagar y con eso se
     * corre el plan entero. Vive en el contrato porque es parte de lo que se firma —quien reclame
     * después «yo no pedí empezar a pagar en marzo» tiene aquí la respuesta— y no en el producto,
     * que sólo declara el tope.
     *
     * <p>Mientras esto no existió, cartera aplicaba el tope del producto a toda cuenta suya. Como
     * el préstamo personal trae BNPL habilitado, <b>ninguno empezaba a pagar cuando debía</b> y no
     * había forma de distinguir «lo pidió» de «se lo pusimos».
     */
    @Column(name = "bnpl_deferral_days")
    private Integer bnplDeferralDays;

    protected Contract() {}

    public static Contract generate(String contractNumber, String signatureMethod) {
        Contract c = new Contract();
        c.contractNumber   = contractNumber;
        c.signatureMethod  = signatureMethod;
        return c;
    }

    /** Called when the contract is signed (PENDING_SIGNATURE → CONTRACT_SIGNED). */
    void complete(String clabeAccount, String documentRef, Instant signedAt, Integer bnplDeferralDays) {
        this.clabeAccount     = clabeAccount;
        this.documentRef      = documentRef;
        this.signedAt         = signedAt;
        // Cero y nulo significan lo mismo —nadie lo pidió— y se guarda nulo para que la columna
        // no distinga dos formas de la misma cosa.
        this.bnplDeferralDays = (bnplDeferralDays != null && bnplDeferralDays > 0) ? bnplDeferralDays : null;
    }

    public String getContractNumber()  { return contractNumber; }
    public String getSignatureMethod() { return signatureMethod; }
    public String getClabeAccount()    { return clabeAccount; }
    public String getDocumentRef()     { return documentRef; }
    public Instant getSignedAt()       { return signedAt; }
    public Integer getBnplDeferralDays() { return bnplDeferralDays; }
}
