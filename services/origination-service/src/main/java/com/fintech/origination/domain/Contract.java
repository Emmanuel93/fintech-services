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

    protected Contract() {}

    public static Contract generate(String contractNumber, String signatureMethod) {
        Contract c = new Contract();
        c.contractNumber   = contractNumber;
        c.signatureMethod  = signatureMethod;
        return c;
    }

    /** Called when the contract is signed (PENDING_SIGNATURE → CONTRACT_SIGNED). */
    void complete(String clabeAccount, String documentRef, Instant signedAt) {
        this.clabeAccount = clabeAccount;
        this.documentRef  = documentRef;
        this.signedAt     = signedAt;
    }

    public String getContractNumber()  { return contractNumber; }
    public String getSignatureMethod() { return signatureMethod; }
    public String getClabeAccount()    { return clabeAccount; }
    public String getDocumentRef()     { return documentRef; }
    public Instant getSignedAt()       { return signedAt; }
}
