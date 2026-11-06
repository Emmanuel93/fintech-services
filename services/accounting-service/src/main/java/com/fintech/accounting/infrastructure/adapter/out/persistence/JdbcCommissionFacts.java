package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.CommissionFacts;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCommissionFacts implements CommissionFacts {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCommissionFacts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Fact f) {
        jdbc.update("""
                INSERT INTO accounting.commission_facts (fact_id, kind, beneficiary_party_id, product_type, batch_id,
                                                         attempt, gross, business_date, period)
                VALUES (:id, :kind, :beneficiary, :product, :batch, :attempt, :gross, :day, to_char(:day, 'YYYYMM'))
                ON CONFLICT (fact_id) DO NOTHING""",
                new MapSqlParameterSource("id", f.factId()).addValue("kind", f.kind().name())
                        .addValue("beneficiary", f.beneficiaryPartyId()).addValue("product", f.productType())
                        .addValue("batch", f.batchId()).addValue("attempt", f.attempt()).addValue("gross", f.gross())
                        .addValue("day", f.businessDate()));
    }
}
