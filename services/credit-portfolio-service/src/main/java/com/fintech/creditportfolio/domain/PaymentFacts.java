package com.fintech.creditportfolio.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Where a payment's money came from, as payments tells it: when it was received (the portfolio's date), its SPEI
 * operation date (the bank's), the funding source (SPEI_CREDIT, CREDIT_BALANCE, DIRECT) and, for a received credit, its
 * id, its tracking key and how this share was applied (REFERENCED, BY_RULE, CREDIT_BALANCE). Travels on to accounting.
 */
public record PaymentFacts(Instant receivedAt, LocalDate operationDate, String fundingSource, UUID inboundCreditId,
                           String allocationMode, String trackingKey, UUID beneficiaryPartyId) {

    public static final PaymentFacts NONE = new PaymentFacts(null, null, null, null, null, null, null);

    public PaymentFacts(Instant receivedAt, LocalDate operationDate, String fundingSource, UUID inboundCreditId,
                        String allocationMode, String trackingKey) {
        this(receivedAt, operationDate, fundingSource, inboundCreditId, allocationMode, trackingKey, null);
    }

    /** A distributor's client paid it himself, with his own reference: it covers his installments first. */
    public boolean byAClient() {
        return beneficiaryPartyId != null;
    }
}
