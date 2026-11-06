package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.collection.CollectionAccount;

import java.util.Optional;
import java.util.UUID;

public interface CollectionAccountRepository {

    CollectionAccount save(CollectionAccount account);

    Optional<CollectionAccount> findById(UUID collectionAccountId);

    Optional<CollectionAccount> findByClabe(String clabe);

    Optional<CollectionAccount> findByCompanyAndParty(UUID companyId, UUID partyId);
}
