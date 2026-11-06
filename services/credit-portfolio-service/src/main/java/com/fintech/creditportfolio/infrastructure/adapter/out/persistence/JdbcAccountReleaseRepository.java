package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.AccountReleaseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesTotals;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcAccountReleaseRepository implements AccountReleaseRepository {

    private static final RowMapper<Release> RELEASE = (rs, i) -> new Release(
            rs.getObject("business_date", LocalDate.class), rs.getObject("credit_account_id", UUID.class),
            new ChargesTotals(rs.getInt("ordinary_charges"), rs.getBigDecimal("ordinary_amount"),
                    rs.getInt("moratorium_charges"), rs.getBigDecimal("moratorium_amount")),
            rs.getString("status"), rs.getString("waiting_for"));

    private final NamedParameterJdbcTemplate jdbc;

    JdbcAccountReleaseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(LocalDate businessDate, UUID creditAccountId, ChargesTotals t) {
        jdbc.update("""
                INSERT INTO credit_portfolio.account_day_releases
                       (business_date, credit_account_id, ordinary_charges, ordinary_amount,
                        moratorium_charges, moratorium_amount)
                VALUES (:d, :a, :oc, :oa, :mc, :ma)
                ON CONFLICT (business_date, credit_account_id) DO UPDATE
                   SET ordinary_charges = EXCLUDED.ordinary_charges, ordinary_amount = EXCLUDED.ordinary_amount,
                       moratorium_charges = EXCLUDED.moratorium_charges, moratorium_amount = EXCLUDED.moratorium_amount
                 WHERE credit_portfolio.account_day_releases.status = 'PENDING'""",
                key(businessDate, creditAccountId)
                        .addValue("oc", t.ordinaryCharges()).addValue("oa", t.ordinaryAmount())
                        .addValue("mc", t.moratoriumCharges()).addValue("ma", t.moratoriumAmount()));
    }

    @Override
    public Optional<Release> find(LocalDate businessDate, UUID creditAccountId) {
        return jdbc.query("""
                SELECT * FROM credit_portfolio.account_day_releases
                 WHERE business_date = :d AND credit_account_id = :a""",
                key(businessDate, creditAccountId), RELEASE).stream().findFirst();
    }

    @Override
    public List<Release> pending(int limit) {
        return jdbc.query("""
                SELECT * FROM credit_portfolio.account_day_releases WHERE status = 'PENDING'
                 ORDER BY business_date, credit_account_id LIMIT :limit""",
                new MapSqlParameterSource("limit", Math.max(limit, 1)), RELEASE);
    }

    @Override
    public void waiting(LocalDate businessDate, UUID creditAccountId, String reason) {
        jdbc.update("""
                UPDATE credit_portfolio.account_day_releases SET waiting_for = :r
                 WHERE business_date = :d AND credit_account_id = :a AND status = 'PENDING'""",
                key(businessDate, creditAccountId).addValue("r", reason));
    }

    @Override
    public void released(LocalDate businessDate, UUID creditAccountId) {
        jdbc.update("""
                UPDATE credit_portfolio.account_day_releases
                   SET status = 'RELEASED', waiting_for = NULL, released_at = now()
                 WHERE business_date = :d AND credit_account_id = :a""", key(businessDate, creditAccountId));
    }

    private static MapSqlParameterSource key(LocalDate d, UUID a) {
        return new MapSqlParameterSource("d", d).addValue("a", a);
    }
}
