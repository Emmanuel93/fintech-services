package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.CollectionAccountProjection;
import com.fintech.creditportfolio.application.port.out.PaymentReferenceSequence;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
class JdbcCollectionAccounts implements CollectionAccountProjection, PaymentReferenceSequence {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCollectionAccounts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(UUID partyId, UUID collectionAccountId, String clabe, String status) {
        jdbc.update("""
                INSERT INTO credit_portfolio.collection_accounts (party_id, collection_account_id, clabe, status)
                VALUES (:party, :id, :clabe, :status)
                ON CONFLICT (party_id) DO UPDATE SET collection_account_id = EXCLUDED.collection_account_id,
                       clabe = EXCLUDED.clabe, status = EXCLUDED.status, updated_at = now()""",
                new MapSqlParameterSource("party", partyId).addValue("id", collectionAccountId)
                        .addValue("clabe", clabe).addValue("status", status));
    }

    @Override
    public Map<UUID, String> activeClabes(Collection<UUID> partyIds) {
        Map<UUID, String> out = new HashMap<>();
        if (partyIds == null || partyIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT party_id, clabe FROM credit_portfolio.collection_accounts
                 WHERE party_id IN (:parties) AND status = 'ACTIVE'""",
                new MapSqlParameterSource("parties", partyIds),
                (ResultSet rs) -> { out.put(rs.getObject("party_id", UUID.class), rs.getString("clabe")); });
        return out;
    }

    @Override
    public long nextBody() {
        Long value = jdbc.getJdbcTemplate().queryForObject("SELECT nextval('credit_portfolio.payment_reference_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("No se pudo obtener el consecutivo de referencia de pago");
        }
        return value;
    }
}
