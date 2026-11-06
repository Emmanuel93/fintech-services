package com.fintech.stp.infrastructure.adapter.out.http.stub;

import com.fintech.shared.time.BusinessClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The amounts a test armed for the stub's scenarios.
 *
 * <p>The local stack runs the stub with {@code scenario-amounts: ARMED}: an end-to-end run arms the amounts its
 * scenarios chose (a placement of $4,000.02 that STP rejects for PLD, one of $3,000.04 the bank returns) and every
 * other order settles, whatever its cents. The platform computes amounts of its own — a commission lot, a funding
 * payment — and eleven of the hundred cents are failures: the distributor suite of 2026-11-04 lost three lots that
 * way ($754.10 timed out every month, $721.05 was cancelled twice, $529.06 was accepted and never settled).
 */
@Component
@ConditionalOnProperty(name = "fintech.stp.gateway.mode", havingValue = "stub")
public class StubScenarioAmounts {

    private final StubScenarioAmountRepository repository;

    StubScenarioAmounts(StubScenarioAmountRepository repository) {
        this.repository = repository;
    }

    /** Replaces the armed amounts with these: arming is idempotent, and what an earlier run armed does not linger. */
    @Transactional
    public List<BigDecimal> arm(Collection<BigDecimal> amounts) {
        repository.deleteAllInBatch();
        Instant now = BusinessClock.current().now();
        repository.saveAll(amounts.stream().map(StubScenarioAmounts::cents).distinct()
                .map(a -> new StubScenarioAmount(a, now)).toList());
        return armed();
    }

    /** The armed amounts, ascending. */
    @Transactional(readOnly = true)
    public List<BigDecimal> armed() {
        return repository.findAll(Sort.by("amount")).stream().map(StubScenarioAmount::getAmount).toList();
    }

    /** Whether a test armed this amount. */
    @Transactional(readOnly = true)
    public boolean isArmed(BigDecimal amount) {
        return amount != null && repository.existsById(cents(amount));
    }

    private static BigDecimal cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }
}
