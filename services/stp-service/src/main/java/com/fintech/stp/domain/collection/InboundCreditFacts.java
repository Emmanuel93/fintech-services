package com.fintech.stp.domain.collection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A credit as STP announces it on one of our CLABEs: the transfer as the payer's bank sent it, plus the two dates of the
 * money. {@code receivedAt} is when it arrived, the portfolio's date; {@code operationDate} is the SPEI operation date,
 * the bank's.
 */
public record InboundCreditFacts(
        UUID companyId,
        long stpId,
        String trackingKey,
        LocalDate operationDate,
        Instant receivedAt,
        BigDecimal amount,
        String beneficiaryClabe,
        String beneficiaryName,
        String payerName,
        String payerAccount,
        Integer payerInstitution,
        String payerTaxId,
        Integer paymentType,
        String concept,
        Long numericReference,
        Long providerSettledAt) {
}
