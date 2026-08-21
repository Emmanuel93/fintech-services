package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.application.port.out.ConsentRepository;
import com.fintech.party.domain.ConsentRecord;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class JpaConsentAdapter implements ConsentRepository {

    private final SpringDataConsentRepository repository;

    public JpaConsentAdapter(SpringDataConsentRepository repository) {
        this.repository = repository;
    }

    @Override
    public ConsentRecord save(ConsentRecord consent) {
        return repository.save(consent);
    }

    @Override
    public List<ConsentRecord> findActiveByPartyId(UUID partyId) {
        return repository.findAllByPartyIdAndStatus(partyId, "GRANTED");
    }

    @Override
    public List<ConsentRecord> findAllByPartyId(UUID partyId) {
        return repository.findAllByPartyId(partyId);
    }
}
