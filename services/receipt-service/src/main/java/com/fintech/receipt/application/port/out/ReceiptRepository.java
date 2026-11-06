package com.fintech.receipt.application.port.out;

import com.fintech.receipt.domain.CutoffFacts;
import com.fintech.receipt.domain.Receipt;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The receipts, by their natural key (line, cycle, client), with the claim that lets one replica generate each. */
public interface ReceiptRepository {

    /** The receipt of this client for this cutoff, registered now if it was not: the same cutoff twice is one receipt. */
    UUID register(CutoffFacts facts, UUID beneficiaryPartyId);

    /** Takes the receipt to generate it, unless another replica holds it (and is not stale). */
    Optional<Claim> claim(UUID receiptId, String owner, Duration staleAfter);

    void markGenerated(UUID receiptId, Generated generated);

    void markFailed(UUID receiptId, String error);

    Optional<Receipt> findById(UUID receiptId);

    /** Receipts by line and cycle, or of a party (as the distributor or as the client), newest first. */
    List<Receipt> search(UUID creditAccountId, Integer cycleNumber, UUID partyId, int limit);

    /** Receipts to try again: FAILED under the attempts cap, or GENERATING past the stale time. */
    List<UUID> retryable(int maxAttempts, Duration staleAfter, int limit);

    Optional<String> findSnapshot(UUID receiptId);

    List<UUID> generated(UUID creditAccountId, Integer cycleNumber, UUID after, int limit);

    void markRegenerated(UUID receiptId, String templateVersion, long documentBytes);

    /** Counts by status, for the self-check and the operation. */
    List<StatusCount> countByStatus();

    record Claim(UUID receiptId, CutoffFacts facts, UUID beneficiaryPartyId, int attempts) {}

    record Generated(String folio, UUID distributorPartyId, String clientName, String clientType, int liveLoans,
                     BigDecimal totalDue, Long paymentReference, String clabe, String snapshotJson,
                     String templateVersion, long documentBytes, String generatedBy) {}

    record StatusCount(String status, long count) {}
}
