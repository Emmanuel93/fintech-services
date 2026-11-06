package com.fintech.creditportfolio.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Each person's collection CLABE, as the SPEI connector assigned it: what their statements tell them to pay to. */
public interface CollectionAccountProjection {

    void upsert(UUID partyId, UUID collectionAccountId, String clabe, String status);

    /** The active collection CLABE of each of {@code partyIds} that has one. */
    Map<UUID, String> activeClabes(Collection<UUID> partyIds);
}
