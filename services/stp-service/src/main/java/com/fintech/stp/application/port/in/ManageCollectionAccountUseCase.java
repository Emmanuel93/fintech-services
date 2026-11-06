package com.fintech.stp.application.port.in;

import com.fintech.stp.domain.collection.CollectionAccount;
import com.fintech.stp.domain.collection.InboundCredit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The back office's view and control of collection CLABEs and of the credits received on them. */
public interface ManageCollectionAccountUseCase {

    Optional<CollectionAccount> findByParty(UUID partyId);

    List<InboundCredit> creditsOf(UUID partyId);

    CollectionAccount block(UUID partyId, String reason);

    CollectionAccount reactivate(UUID partyId, String reason);
}
