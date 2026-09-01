package com.fintech.origination.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot emitted when the contract is signed.
 * Consumed by credit-portfolio-service to create the live CreditAccount.
 *
 * All pricing terms are frozen here — credit-portfolio never reads the catalog at runtime.
 * contractId = applicationId (used for correlation back to origination).
 *
 * obligorPartyId = prospectId for now (TODO: resolve actual partyId from party-service).
 */
public class CreditProductCreationRequestedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final UUID applicationId;
    private final String contractNumber;
    private final UUID obligorPartyId;
    private final String productCode;
    private final Integer productVersion;
    private final String productType;
    private final String productBehavior;
    private final BigDecimal approvedAmount;
    private final BigDecimal approvedLine;
    private final Integer assignedTerm;
    private final BigDecimal nominalRate;
    private final BigDecimal moratoriumRate;
    private final String amortizationType;
    private final BigDecimal openingFeeRate;
    private final String clabeAccount;
    private final String riskTier;
    private final String promoterCode;
    // Identidad del beneficiario, congelada al firmar. STP la exige para firmar la orden
    // (nombre = posición 14, RFC/CURP = posición 16 de la cadena original) y el conector no
    // puede preguntarle al dominio de personas sin acoplarse a él: viaja en el hecho.
    private final String obligorName;
    private final String obligorTaxId;
    /** Días de BNPL que el cliente pidió al firmar; nulo si no pidió ninguno. */
    private final Integer bnplDeferralDays;

    public CreditProductCreationRequestedEvent(
            UUID applicationId,
            String contractNumber,
            UUID obligorPartyId,
            String productCode,
            Integer productVersion,
            String productType,
            String productBehavior,
            BigDecimal approvedAmount,
            BigDecimal approvedLine,
            Integer assignedTerm,
            BigDecimal nominalRate,
            BigDecimal moratoriumRate,
            String amortizationType,
            BigDecimal openingFeeRate,
            String clabeAccount,
            String riskTier,
            String promoterCode,
            String obligorName,
            String obligorTaxId,
            Integer bnplDeferralDays) {
        this.eventId        = UUID.randomUUID().toString();
        this.occurredOn     = Instant.now();
        this.applicationId  = applicationId;
        this.contractNumber = contractNumber;
        this.obligorPartyId = obligorPartyId;
        this.productCode    = productCode;
        this.productVersion = productVersion;
        this.productType    = productType;
        this.productBehavior = productBehavior;
        this.approvedAmount = approvedAmount;
        this.approvedLine   = approvedLine;
        this.assignedTerm   = assignedTerm;
        this.nominalRate    = nominalRate;
        this.moratoriumRate = moratoriumRate;
        this.amortizationType = amortizationType;
        this.openingFeeRate = openingFeeRate;
        this.clabeAccount   = clabeAccount;
        this.riskTier       = riskTier;
        this.promoterCode   = promoterCode;
        this.obligorName    = obligorName;
        this.obligorTaxId   = obligorTaxId;
        this.bnplDeferralDays = bnplDeferralDays;
    }

    public String getEventId()             { return eventId; }
    public Instant getOccurredOn()         { return occurredOn; }
    public UUID getApplicationId()         { return applicationId; }
    public String getContractNumber()      { return contractNumber; }
    public UUID getObligorPartyId()        { return obligorPartyId; }
    public String getProductCode()         { return productCode; }
    public Integer getProductVersion()     { return productVersion; }
    public String getProductType()         { return productType; }
    public String getProductBehavior()     { return productBehavior; }
    public BigDecimal getApprovedAmount()  { return approvedAmount; }
    public BigDecimal getApprovedLine()    { return approvedLine; }
    public Integer getAssignedTerm()       { return assignedTerm; }
    public BigDecimal getNominalRate()     { return nominalRate; }
    public BigDecimal getMoratoriumRate()  { return moratoriumRate; }
    public String getAmortizationType()    { return amortizationType; }
    public BigDecimal getOpeningFeeRate()  { return openingFeeRate; }
    public String getClabeAccount()        { return clabeAccount; }
    public String getRiskTier()            { return riskTier; }
    public String getPromoterCode()        { return promoterCode; }
    public String getObligorName()         { return obligorName; }
    public String getObligorTaxId()        { return obligorTaxId; }
    public Integer getBnplDeferralDays()  { return bnplDeferralDays; }
}
