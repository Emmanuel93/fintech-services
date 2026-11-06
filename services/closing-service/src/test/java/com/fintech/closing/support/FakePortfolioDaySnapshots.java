package com.fintech.closing.support;

import com.fintech.closing.application.port.out.PortfolioDaySnapshots;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The portfolio's day snapshot without the portfolio: derived from closing's own projection (principal
 * rewound to D with the recorded movements, current days past due), so the integration tests keep
 * describing an account once. A test can override an account's values or leave it out.
 */
public class FakePortfolioDaySnapshots implements PortfolioDaySnapshots {

    private final JdbcTemplate jdbc;
    private final Map<UUID, BigDecimal> principals = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> daysDelinquent = new ConcurrentHashMap<>();
    private final Set<UUID> omitted = ConcurrentHashMap.newKeySet();

    public FakePortfolioDaySnapshots(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void principalOf(UUID creditAccountId, String principal) {
        principals.put(creditAccountId, new BigDecimal(principal));
    }

    public void daysDelinquentOf(UUID creditAccountId, int days) {
        daysDelinquent.put(creditAccountId, days);
    }

    public void omit(UUID creditAccountId) {
        omitted.add(creditAccountId);
    }

    public void reset() {
        principals.clear();
        daysDelinquent.clear();
        omitted.clear();
    }

    @Override
    public List<AccountSnapshot> lookup(LocalDate businessDate, Collection<UUID> creditAccountIds) {
        List<UUID> ids = creditAccountIds.stream().filter(id -> !omitted.contains(id)).toList();
        if (ids.isEmpty()) return List.of();
        Instant takenAt = Instant.now();
        List<AccountSnapshot> out = new ArrayList<>();
        for (UUID id : ids) {
            jdbc.query("""
                    SELECT p.credit_account_id, p.days_delinquent,
                           p.principal_balance - coalesce((SELECT sum(m.principal_delta) FROM closing.balance_movements m
                                                            WHERE m.credit_account_id = p.credit_account_id AND m.value_date > ?), 0) AS principal
                      FROM closing.account_close_profiles p WHERE p.credit_account_id = ?""",
                    rs -> {
                        UUID account = rs.getObject("credit_account_id", UUID.class);
                        out.add(new AccountSnapshot(account,
                                principals.getOrDefault(account, rs.getBigDecimal("principal")),
                                daysDelinquent.getOrDefault(account, rs.getInt("days_delinquent")), takenAt));
                    }, businessDate, id);
        }
        return out;
    }
}
