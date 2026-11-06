package com.fintech.stp.domain.collection;

import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A credit received on one of our CLABEs, as it entered the inbox, with the decision taken while STP waited.
 *
 * <p>Immutable once recorded: what arrived arrived. A returned credit stays here too: it explains the matching
 * return in the bank's statement.
 */
@Entity
@Table(schema = "stp", name = "inbound_credits")
public class InboundCredit {

    @Id
    @Column(name = "inbound_credit_id", nullable = false, updatable = false)
    private UUID inboundCreditId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "stp_id", nullable = false, updatable = false)
    private long stpId;

    @Column(name = "tracking_key", nullable = false, updatable = false)
    private String trackingKey;

    @Column(name = "operation_date", nullable = false, updatable = false)
    private LocalDate operationDate;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "beneficiary_clabe", nullable = false, updatable = false)
    private String beneficiaryClabe;

    @Column(name = "beneficiary_name", updatable = false)
    private String beneficiaryName;

    @Column(name = "payer_name", updatable = false)
    private String payerName;

    @Column(name = "payer_account", updatable = false)
    private String payerAccount;

    @Column(name = "payer_institution", updatable = false)
    private Integer payerInstitution;

    @Column(name = "payer_tax_id", updatable = false)
    private String payerTaxId;

    @Column(name = "payment_type", updatable = false)
    private Integer paymentType;

    @Column(name = "concept", updatable = false)
    private String concept;

    @Column(name = "numeric_reference", updatable = false)
    private Long numericReference;

    @Column(name = "provider_settled_at", updatable = false)
    private Long providerSettledAt;

    @Column(name = "collection_account_id", updatable = false)
    private UUID collectionAccountId;

    @Column(name = "party_id", updatable = false)
    private UUID partyId;

    @Column(name = "decision", nullable = false, updatable = false)
    private String decision;

    @Column(name = "return_cause", updatable = false)
    private Integer returnCause;

    @Column(name = "return_reason", updatable = false)
    private String returnReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InboundCredit() {
    }

    /** Kept: it belongs to {@code account}, whose owner it will be applied to. */
    public static InboundCredit confirmed(InboundCreditFacts facts, CollectionAccount account) {
        InboundCredit credit = from(facts);
        credit.collectionAccountId = Objects.requireNonNull(account, "account").getCollectionAccountId();
        credit.partyId = account.getPartyId();
        credit.decision = CreditDecision.CONFIRMED.name();
        return credit;
    }

    /** Sent back to the payer: STP returns it at once and it never reaches a loan. */
    public static InboundCredit returned(InboundCreditFacts facts, CollectionAccount account, ReturnCause cause) {
        InboundCredit credit = from(facts);
        if (account != null) {
            credit.collectionAccountId = account.getCollectionAccountId();
            credit.partyId = account.getPartyId();
        }
        credit.decision = CreditDecision.RETURNED.name();
        credit.returnCause = Objects.requireNonNull(cause, "cause").code();
        credit.returnReason = cause.description();
        return credit;
    }

    private static InboundCredit from(InboundCreditFacts facts) {
        InboundCredit credit = new InboundCredit();
        credit.inboundCreditId = UUID.randomUUID();
        credit.companyId = facts.companyId();
        credit.stpId = facts.stpId();
        credit.trackingKey = facts.trackingKey();
        credit.operationDate = facts.operationDate();
        credit.receivedAt = facts.receivedAt();
        credit.amount = facts.amount() == null ? BigDecimal.ZERO : facts.amount().setScale(2, RoundingMode.HALF_UP);
        credit.beneficiaryClabe = facts.beneficiaryClabe();
        credit.beneficiaryName = facts.beneficiaryName();
        credit.payerName = facts.payerName();
        credit.payerAccount = facts.payerAccount();
        credit.payerInstitution = facts.payerInstitution();
        credit.payerTaxId = facts.payerTaxId();
        credit.paymentType = facts.paymentType();
        credit.concept = facts.concept();
        credit.numericReference = facts.numericReference();
        credit.providerSettledAt = facts.providerSettledAt();
        credit.createdAt = BusinessClock.current().now();
        return credit;
    }

    public CreditDecision decision() {
        return CreditDecision.valueOf(decision);
    }

    public boolean isConfirmed() {
        return decision() == CreditDecision.CONFIRMED;
    }

    public UUID getInboundCreditId() { return inboundCreditId; }
    public UUID getCompanyId() { return companyId; }
    public long getStpId() { return stpId; }
    public String getTrackingKey() { return trackingKey; }
    public LocalDate getOperationDate() { return operationDate; }
    public Instant getReceivedAt() { return receivedAt; }
    public BigDecimal getAmount() { return amount; }
    public String getBeneficiaryClabe() { return beneficiaryClabe; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getPayerName() { return payerName; }
    public String getPayerAccount() { return payerAccount; }
    public Integer getPayerInstitution() { return payerInstitution; }
    public String getPayerTaxId() { return payerTaxId; }
    public Integer getPaymentType() { return paymentType; }
    public String getConcept() { return concept; }
    public Long getNumericReference() { return numericReference; }
    public Long getProviderSettledAt() { return providerSettledAt; }
    public UUID getCollectionAccountId() { return collectionAccountId; }
    public UUID getPartyId() { return partyId; }
    public Integer getReturnCause() { return returnCause; }
    public String getReturnReason() { return returnReason; }
    public Instant getCreatedAt() { return createdAt; }
}
