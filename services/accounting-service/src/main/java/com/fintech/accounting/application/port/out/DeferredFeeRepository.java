package com.fintech.accounting.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The opening fees being recognized over the life of their loans. */
public interface DeferredFeeRepository {

    /** An opening fee charged to the client ({@link #FEE}) or a transaction cost paid to originate the loan ({@link #COST}). */
    String FEE = "FEE";
    String COST = "COST";
    /** The opening fee of a funding line: a transaction cost of the institution's own liability. */
    String FUNDING = "FUNDING";

    record DeferredFee(UUID id, String sourceEventId, UUID creditAccountId, UUID obligorPartyId, String orgUnitCode,
                       BigDecimal total, BigDecimal recognized, int months, String firstPeriod, String kind) {

        public DeferredFee(UUID id, String sourceEventId, UUID creditAccountId, UUID obligorPartyId, String orgUnitCode,
                           BigDecimal total, BigDecimal recognized, int months, String firstPeriod) {
            this(id, sourceEventId, creditAccountId, obligorPartyId, orgUnitCode, total, recognized, months, firstPeriod, FEE);
        }

        public BigDecimal pending() {
            return total.subtract(recognized);
        }

        public boolean isCost() {
            return COST.equals(kind);
        }

        public boolean isFunding() {
            return FUNDING.equals(kind);
        }
    }

    /** Registers it once per fact: a redelivered charge does not defer the fee twice. */
    boolean register(DeferredFee fee);

    List<DeferredFee> active();

    List<DeferredFee> activeOf(UUID creditAccountId);

    /** The deferral registered by a fact, active or not. */
    Optional<DeferredFee> findBySource(String sourceEventId);

    /** Sets what has been recognized so far; DONE once it is all of it. */
    void recognize(UUID id, BigDecimal recognizedSoFar);

    /** Takes {@code amount} out of what is still deferred (a reversed or waived fee); DONE once nothing is left. */
    void reduceTotal(UUID id, BigDecimal amount);
}
