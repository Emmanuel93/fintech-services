package com.fintech.statement.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the statements' self-checks read from their own data, and nothing else. */
public interface StatementSelfCheckQueries {

    /** A sealed cutoff run whose statements are not one per account it closed. */
    record RunGap(UUID runId, LocalDate businessDate, int closed, int statements) {}

    /**
     * A statement whose balance is not the one its cutoff sealed. Only the balance: what the statement asks to pay (the
     * period's due, its minimum) it works out itself, product by product, and is not the cutoff's figure.
     */
    record BalanceGap(UUID statementId, String contractNumber, LocalDate cutoffDate, BigDecimal balance,
                      BigDecimal cutoffBalance) {}

    /** A generated statement without its document, or with one of another size. */
    record MissingDocument(UUID statementId, String contractNumber, Long documentBytes, Long storedBytes) {}

    /** A statement not generated although its cutoff is days old. */
    record Ungenerated(UUID statementId, String contractNumber, LocalDate cutoffDate, String status, int attempts,
                       String lastError) {}

    int sealedRuns();

    List<RunGap> runGaps();

    int statements();

    List<BalanceGap> balanceGaps();

    List<MissingDocument> missingDocuments();

    List<Ungenerated> ungeneratedBefore(LocalDate cutoffDate);
}
