package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.CollectionAccountRepository;
import com.fintech.stp.domain.collection.CollectionAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface JpaCollectionAccountRepository
        extends JpaRepository<CollectionAccount, UUID>, CollectionAccountRepository {

    @Override
    Optional<CollectionAccount> findByClabe(String clabe);

    @Override
    @Query("SELECT a FROM CollectionAccount a WHERE a.companyId = :companyId AND a.partyId = :partyId")
    Optional<CollectionAccount> findByCompanyAndParty(@Param("companyId") UUID companyId, @Param("partyId") UUID partyId);
}
