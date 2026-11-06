package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.FundingBalanceRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Repository
class JdbcFundingBalanceRepository implements FundingBalanceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcFundingBalanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID fundingSourceId, String kind, String counterpartyName, String contractReference,
                       BigDecimal contributed, BigDecimal principal, BigDecimal interestPayable,
                       LocalDate accruedThrough, String status, UUID movementId) {
        jdbc.update("""
                INSERT INTO accounting.funding_balances (funding_source_id, kind, counterparty_name, contract_reference,
                       contributed, principal, interest_payable, accrued_through, status, last_movement_id, updated_at)
                VALUES (:id, :kind, :name, :contract, :contributed, :principal, :interest, :accrued, :status, :movement, now())
                ON CONFLICT (funding_source_id) DO UPDATE
                   SET contributed = EXCLUDED.contributed, principal = EXCLUDED.principal,
                       interest_payable = EXCLUDED.interest_payable,
                       accrued_through = GREATEST(accounting.funding_balances.accrued_through, EXCLUDED.accrued_through),
                       status = EXCLUDED.status, last_movement_id = EXCLUDED.last_movement_id, updated_at = now()""",
                new MapSqlParameterSource("id", fundingSourceId).addValue("kind", kind).addValue("name", counterpartyName)
                        .addValue("contract", contractReference).addValue("contributed", nz(contributed))
                        .addValue("principal", nz(principal)).addValue("interest", nz(interestPayable))
                        .addValue("accrued", accruedThrough).addValue("status", status == null ? "ACTIVE" : status)
                        .addValue("movement", movementId));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
