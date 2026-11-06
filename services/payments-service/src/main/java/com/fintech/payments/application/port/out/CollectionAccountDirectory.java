package com.fintech.payments.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Each payer's collection CLABE, as the SPEI connector assigned it, and the request to assign one. */
public interface CollectionAccountDirectory {

    /** Asks the connector for a collection CLABE for {@code partyId} (idempotent on the connector's side). */
    void request(UUID partyId);

    void record(UUID partyId, UUID collectionAccountId, String clabe, String status);

    Optional<CollectionAccountView> findByParty(UUID partyId);

    record CollectionAccountView(UUID partyId, UUID collectionAccountId, String clabe, String status) {}
}
