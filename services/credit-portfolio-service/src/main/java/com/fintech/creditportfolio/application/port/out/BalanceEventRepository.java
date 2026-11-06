package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import com.fintech.creditportfolio.domain.BalanceEvent;

public interface BalanceEventRepository {

    BalanceEvent save(BalanceEvent event);

    /** Idempotency guard — true if this source event was already applied. */
    boolean existsBySourceEventId(String sourceEventId);

    /** The entry a source event produced, if any. */
    Optional<BalanceEvent> findBySourceEventId(String sourceEventId);

    /** The entry recorded just before this one on the same account (by application order), if any. */
    Optional<BalanceEvent> findPrevious(BalanceEvent event);

    /** Returns how many balance events have been recorded for a given account (for test assertions). */
    long countByAccountId(UUID accountId);

    /**
     * El libro de la cuenta en un período por <b>fecha valor</b>, ambos días incluidos, en el orden en
     * que se aplicó: lo que pinta el estado de cuenta.
     */
    List<BalanceEvent> findBetween(UUID accountId, LocalDate from, LocalDate to);

    /** El último movimiento con fecha valor anterior a un día: los saldos con los que abre el período. */
    Optional<BalanceEvent> findLastBefore(UUID accountId, LocalDate before);

    /** Interest, IVA, moratorium and fees charged, net of reversals and waivers, valued within [from, to]. */
    BigDecimal chargesBetween(UUID accountId, LocalDate from, LocalDate to);

    /** Payments applied minus payments returned, valued up to {@code to}. */
    BigDecimal netPaymentsUpTo(UUID accountId, LocalDate to);

    /** The account's payments and returns, oldest first: what covers its statements (see StatementCoverage). */
    List<BalanceEvent> paymentsOf(UUID accountId);
}
