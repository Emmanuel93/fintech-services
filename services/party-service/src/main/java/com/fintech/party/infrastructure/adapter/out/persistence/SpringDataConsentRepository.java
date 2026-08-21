package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.domain.ConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataConsentRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findAllByPartyId(UUID partyId);

    List<ConsentRecord> findAllByPartyIdAndStatus(UUID partyId, String status);
}
