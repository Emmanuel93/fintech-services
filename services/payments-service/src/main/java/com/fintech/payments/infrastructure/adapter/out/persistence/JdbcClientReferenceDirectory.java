package com.fintech.payments.infrastructure.adapter.out.persistence;

import com.fintech.payments.application.port.out.ClientReferenceDirectory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcClientReferenceDirectory implements ClientReferenceDirectory {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcClientReferenceDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(long paymentReference, UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId) {
        // A redelivered assignment changes nothing: a reference names one client of one line for good.
        jdbc.update("""
                INSERT INTO payments.client_references (payment_reference, credit_account_id, obligor_party_id, beneficiary_party_id)
                VALUES (:ref, :line, :holder, :client)
                ON CONFLICT (payment_reference) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("ref", paymentReference)
                .addValue("line", creditAccountId)
                .addValue("holder", obligorPartyId)
                .addValue("client", beneficiaryPartyId));
    }

    @Override
    public Optional<ClientReference> find(long paymentReference) {
        return jdbc.query("""
                SELECT payment_reference, credit_account_id, obligor_party_id, beneficiary_party_id
                  FROM payments.client_references WHERE payment_reference = :ref
                """, new MapSqlParameterSource("ref", paymentReference),
                (rs, i) -> new ClientReference(rs.getLong("payment_reference"), rs.getObject("credit_account_id", UUID.class),
                        rs.getObject("obligor_party_id", UUID.class), rs.getObject("beneficiary_party_id", UUID.class)))
                .stream().findFirst();
    }
}
