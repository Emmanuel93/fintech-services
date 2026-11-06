package com.fintech.accounting.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Treasury's cash book as accounting receives it; 1101 is reconciled against it at the close. */
public interface TreasuryCashBookRepository {

    /** Keeps the movement once (by its id); a redelivery changes nothing. */
    default void record(UUID movementId, String movementType, String direction, BigDecimal amount, LocalDate businessDate,
                        String reference, String sourceEventId) {
        record(movementId, movementType, direction, amount, businessDate, reference, sourceEventId, businessDate, null);
    }

    /**
     * @param businessDate  the SPEI operation date: the bank's
     * @param portfolioDate the portfolio's date for the same money (received, or disposed)
     */
    void record(UUID movementId, String movementType, String direction, BigDecimal amount, LocalDate businessDate,
                String reference, String sourceEventId, LocalDate portfolioDate, String trackingKey);
}
