package com.fintech.payments.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A credit received on a payer's CLABE is applied: to the referenced loan, by seniority, the rest in favor. */
public interface ApplyReceivedCreditUseCase {

    void apply(ReceivedCreditCommand credit);

    record ReceivedCreditCommand(UUID inboundCreditId, UUID partyId, BigDecimal amount, Instant receivedAt,
                                 LocalDate operationDate, String trackingKey, Long numericReference) {}
}
