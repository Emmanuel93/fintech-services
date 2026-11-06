package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.collection.CollectionAccount;
import com.fintech.stp.domain.collection.InboundCredit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The integration events of collection, as they travel. */
public final class CollectionEvents {

    public static final String TOPIC_COLLECTION_ACCOUNT_ASSIGNED = "stp.collection-account-assigned";
    public static final String TOPIC_CREDIT_RECEIVED = "stp.credit-received";

    private CollectionEvents() {
    }

    /** A person has a collection CLABE: the portfolio prints it on the statements, the back office shows it. */
    public record CollectionAccountAssigned(String eventId, UUID collectionAccountId, UUID companyId, UUID partyId,
                                            String clabe, String status, Instant occurredOn) {
        public static CollectionAccountAssigned of(CollectionAccount account) {
            return new CollectionAccountAssigned("COLLECTION-ACCOUNT:" + account.getCollectionAccountId() + ":"
                    + account.status().name(), account.getCollectionAccountId(), account.getCompanyId(),
                    account.getPartyId(), account.getClabe(), account.status().name(), account.getUpdatedAt());
        }
    }

    /**
     * A credit received on one of our CLABEs, confirmed or returned. Carries both dates of the money: when it arrived
     * ({@code receivedAt}, the portfolio's) and the SPEI operation date ({@code operationDate}, the bank's).
     */
    public record CreditReceived(String eventId, UUID inboundCreditId, UUID companyId, long stpId, String trackingKey,
                                 LocalDate operationDate, Instant receivedAt, BigDecimal amount,
                                 String beneficiaryClabe, UUID collectionAccountId, UUID partyId,
                                 Long numericReference, String concept, String payerName, String payerAccount,
                                 Integer payerInstitution, String payerTaxId, Integer paymentType, String decision,
                                 Integer returnCause, String returnReason) {
        public static CreditReceived of(InboundCredit credit) {
            return new CreditReceived("CREDIT:" + credit.getInboundCreditId(), credit.getInboundCreditId(),
                    credit.getCompanyId(), credit.getStpId(), credit.getTrackingKey(), credit.getOperationDate(),
                    credit.getReceivedAt(), credit.getAmount(), credit.getBeneficiaryClabe(),
                    credit.getCollectionAccountId(), credit.getPartyId(), credit.getNumericReference(),
                    credit.getConcept(), credit.getPayerName(), credit.getPayerAccount(), credit.getPayerInstitution(),
                    credit.getPayerTaxId(), credit.getPaymentType(), credit.decision().name(),
                    credit.getReturnCause(), credit.getReturnReason());
        }
    }
}
