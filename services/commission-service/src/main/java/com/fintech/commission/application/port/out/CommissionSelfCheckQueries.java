package com.fintech.commission.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What the commission service's self-checks read from its own data, and nothing else. */
public interface CommissionSelfCheckQueries {

    /** A lot as it was opened, next to what its records add up to. */
    record LotFigures(UUID batchId, UUID beneficiaryPartyId, String productType, String period, String status,
                      BigDecimal subtotal, BigDecimal iva, BigDecimal isrWithheld, BigDecimal ivaWithheld,
                      BigDecimal total, BigDecimal net, int recordsCount,
                      BigDecimal recordsSubtotal, BigDecimal recordsIva, int recordsFound,
                      int payoutAttempts, String failureReason, Instant createdAt) {}

    /** An accrual whose reversals took back more than it accrued. */
    record OverReversal(UUID commissionId, BigDecimal amount, BigDecimal iva, BigDecimal reversed, BigDecimal ivaReversed) {}

    /** What was accrued and left in no lot, by beneficiary and product, as of a moment. */
    record OpenGroup(UUID beneficiaryPartyId, String productType, BigDecimal subtotal, int records) {}

    /** A liquidation run: the period it liquidated and when it ran. */
    record Run(String period, Instant runAt) {}

    /** The lots of a period, or every lot with none. */
    List<LotFigures> lots(String period);

    List<OverReversal> overReversals();

    /** The accruals still open that the run at {@code runAt} saw: of a period up to {@code period}, accrued by then. */
    List<OpenGroup> openAsOf(String period, Instant runAt);

    /** The latest liquidation run. */
    Optional<Run> lastRun();
}
