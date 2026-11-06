package com.fintech.accounting.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** The payers' balances in favor movement by movement, as payments reports them; 2102 is reconciled against it. */
public interface CustomerBalanceLedger {

    /** Keeps the movement once (by its id); a redelivery changes nothing. */
    void record(UUID movementId, UUID partyId, String movementType, BigDecimal amount, UUID inboundCreditId,
                UUID paymentOrderId, UUID creditAccountId, LocalDate businessDate);
}
