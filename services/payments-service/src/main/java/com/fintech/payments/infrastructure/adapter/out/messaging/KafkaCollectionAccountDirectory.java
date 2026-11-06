package com.fintech.payments.infrastructure.adapter.out.messaging;

import com.fintech.payments.application.port.out.CollectionAccountDirectory;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * The payers' collection CLABEs: asked of the SPEI connector on its command topic, recorded as it answers. The
 * request goes after commit, and it is idempotent on the connector's side, so asking again for the same payer (a
 * second loan) does no harm.
 */
@Component
public class KafkaCollectionAccountDirectory implements CollectionAccountDirectory {

    static final String TOPIC_REQUEST = "stp.collection-account-requested";

    private static final Logger log = LoggerFactory.getLogger(KafkaCollectionAccountDirectory.class);

    record CollectionAccountRequested(String requestId, UUID holderId, String holderName, String holderTaxId) {}

    private final KafkaTemplate<String, Object> kafka;
    private final NamedParameterJdbcTemplate jdbc;

    public KafkaCollectionAccountDirectory(KafkaTemplate<String, Object> kafka, NamedParameterJdbcTemplate jdbc) {
        this.kafka = kafka;
        this.jdbc = jdbc;
    }

    @Override
    public void request(UUID partyId) {
        if (partyId == null || findByParty(partyId).isPresent()) {
            return;
        }
        CollectionAccountRequested request = new CollectionAccountRequested("CLABE:" + partyId, partyId, null, null);
        Runnable send = () -> kafka.send(TOPIC_REQUEST, partyId.toString(), request).whenComplete((r, ex) -> {
            if (ex != null) log.error("No se pudo pedir la CLABE de cobro de {}: {}", partyId, ex.getMessage());
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    @Override
    public void record(UUID partyId, UUID collectionAccountId, String clabe, String status) {
        jdbc.update("""
                INSERT INTO payments.collection_accounts (party_id, collection_account_id, clabe, status, updated_at)
                VALUES (:party, :id, :clabe, :status, :at)
                ON CONFLICT (party_id) DO UPDATE SET collection_account_id = EXCLUDED.collection_account_id,
                       clabe = EXCLUDED.clabe, status = EXCLUDED.status, updated_at = EXCLUDED.updated_at""",
                new MapSqlParameterSource("party", partyId).addValue("id", collectionAccountId).addValue("clabe", clabe)
                        .addValue("status", status).addValue("at", Timestamp.from(BusinessClock.current().now())));
    }

    @Override
    public Optional<CollectionAccountView> findByParty(UUID partyId) {
        return jdbc.query("""
                SELECT party_id, collection_account_id, clabe, status FROM payments.collection_accounts
                 WHERE party_id = :party""", new MapSqlParameterSource("party", partyId),
                (rs, i) -> new CollectionAccountView(rs.getObject("party_id", UUID.class),
                        rs.getObject("collection_account_id", UUID.class), rs.getString("clabe"),
                        rs.getString("status"))).stream().findFirst();
    }
}
