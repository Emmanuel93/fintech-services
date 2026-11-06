package com.fintech.charges.support;

import com.fintech.charges.application.port.out.PortfolioDaySnapshots;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The portfolio's day snapshot without the portfolio: built from the schedules charges itself holds, so
 * the integration tests describe the account once. A test can override what the snapshot says about an
 * account (its principal), leave an account out, or make the portfolio unavailable.
 */
public class FakePortfolioDaySnapshots implements PortfolioDaySnapshots {

    private final JdbcTemplate jdbc;
    private final Map<UUID, BigDecimal> principals = new ConcurrentHashMap<>();
    private final Map<UUID, BigDecimal> interestBearing = new ConcurrentHashMap<>();
    private final Set<UUID> omitted = ConcurrentHashMap.newKeySet();
    private volatile String unavailable;

    public FakePortfolioDaySnapshots(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The snapshot reports this principal for the account, whatever the schedule holds. */
    public void principalOf(UUID creditAccountId, String principal) {
        principals.put(creditAccountId, new BigDecimal(principal));
    }

    /** The snapshot reports this interest-bearing principal (part of the principal is deferred interest-free). */
    public void interestBearingOf(UUID creditAccountId, String principal) {
        interestBearing.put(creditAccountId, new BigDecimal(principal));
    }

    /** The portfolio does not count this account any more: it is not in the snapshot. */
    public void omit(UUID creditAccountId) {
        omitted.add(creditAccountId);
    }

    /** The portfolio cannot take the snapshot yet, with this reason. */
    public void unavailable(String reason) {
        unavailable = reason;
    }

    public void available() {
        unavailable = null;
    }

    public void reset() {
        principals.clear();
        interestBearing.clear();
        omitted.clear();
        unavailable = null;
    }

    @Override
    public Status take(LocalDate businessDate) {
        if (unavailable != null) return new Status(false, unavailable, 0);
        return new Status(true, "fake", rows(businessDate).size());
    }

    @Override
    public List<AccountSnapshot> page(LocalDate businessDate, UUID after, int size) {
        return rows(businessDate).stream()
                .filter(r -> after == null || r.creditAccountId().toString().compareTo(after.toString()) > 0)
                .limit(size)
                .toList();
    }

    private List<AccountSnapshot> rows(LocalDate businessDate) {
        Instant takenAt = Instant.now();
        return jdbc.query("""
                SELECT credit_account_id, principal_balance, overdue_principal, oldest_due_date
                  FROM charges.accrual_schedules WHERE status = 'ACTIVE'""",
                (rs, i) -> {
                    UUID account = rs.getObject("credit_account_id", UUID.class);
                    BigDecimal overdue = rs.getBigDecimal("overdue_principal");
                    LocalDate oldest = rs.getObject("oldest_due_date", LocalDate.class);
                    int dpd = oldest == null ? 0 : (int) ChronoUnit.DAYS.between(oldest, businessDate);
                    BigDecimal principal = principals.getOrDefault(account, rs.getBigDecimal("principal_balance"));
                    return new AccountSnapshot(account, principal, overdue == null ? BigDecimal.ZERO : overdue, oldest,
                            Math.max(dpd, 0), 1L, takenAt, interestBearing.getOrDefault(account, principal));
                }).stream()
                .filter(r -> !omitted.contains(r.creditAccountId()))
                .sorted(Comparator.comparing(r -> r.creditAccountId().toString()))
                .toList();
    }
}
