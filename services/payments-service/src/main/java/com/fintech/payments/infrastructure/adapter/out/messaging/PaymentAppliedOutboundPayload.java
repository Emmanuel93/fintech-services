package com.fintech.payments.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbound {@code payments.payment-applied}.
 *
 * <ul>
 *   <li>{@code valueDate}: the business day the money was received, the portfolio's date for the payment.</li>
 *   <li>{@code receivedAt}: the moment it was received (a credit at 23:00 belongs to today, one at 00:05 to tomorrow).</li>
 *   <li>{@code operationDate}: the SPEI operation date, the day the bank books it; null when no money moved
 *       ({@code CREDIT_BALANCE}).</li>
 *   <li>{@code fundingSource}: {@code SPEI_CREDIT}, {@code CREDIT_BALANCE} or {@code DIRECT}.</li>
 *   <li>{@code inboundCreditId}, {@code allocationSeq}, {@code allocationMode}, {@code trackingKey}: which received
 *       credit this payment is part of, in which place of its prelation and how it was applied.</li>
 * </ul>
 */
public record PaymentAppliedOutboundPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount,
        long snapshotVersion,
        LocalDate valueDate,
        UUID obligorPartyId,
        Instant receivedAt,
        LocalDate operationDate,
        String fundingSource,
        UUID inboundCreditId,
        Integer allocationSeq,
        String allocationMode,
        String trackingKey,
        /** The distributor's client who paid with his own reference; null for any other payment. */
        UUID beneficiaryPartyId
) {
    public PaymentAppliedOutboundPayload(String eventId, UUID creditAccountId, BigDecimal amount, long snapshotVersion,
                                         LocalDate valueDate, UUID obligorPartyId, Instant receivedAt,
                                         LocalDate operationDate, String fundingSource, UUID inboundCreditId,
                                         Integer allocationSeq, String allocationMode, String trackingKey) {
        this(eventId, creditAccountId, amount, snapshotVersion, valueDate, obligorPartyId, receivedAt, operationDate,
                fundingSource, inboundCreditId, allocationSeq, allocationMode, trackingKey, null);
    }

    public PaymentAppliedOutboundPayload(String eventId, UUID creditAccountId, BigDecimal amount, long snapshotVersion,
                                         LocalDate valueDate) {
        this(eventId, creditAccountId, amount, snapshotVersion, valueDate, null, null, null, "DIRECT", null, null,
                null, null);
    }

    public PaymentAppliedOutboundPayload(String eventId, UUID creditAccountId, BigDecimal amount, long snapshotVersion) {
        this(eventId, creditAccountId, amount, snapshotVersion, null);
    }
}
