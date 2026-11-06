package com.fintech.receipt.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A client's payment receipt for one statement of his distributor's line, as stored.
 *
 * @param totalDue         what he pays this fortnight, without her discount
 * @param paymentReference his reference on her line: a deposit to her CLABE with it lands on his loans
 */
public record Receipt(UUID receiptId, UUID creditAccountId, int cycleNumber, UUID beneficiaryPartyId,
                      UUID distributorPartyId, LocalDate periodStart, LocalDate cutoffDate, LocalDate paymentDueDate,
                      ReceiptStatus status, int attempts, String lastError, String folio, String clientName,
                      String clientType, Integer liveLoans, BigDecimal totalDue, Long paymentReference, String clabe,
                      String templateVersion, Long documentBytes, Instant generatedAt, Instant createdAt) {}
