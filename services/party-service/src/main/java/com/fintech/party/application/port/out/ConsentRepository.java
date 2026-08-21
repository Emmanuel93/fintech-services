package com.fintech.party.application.port.out;

import com.fintech.party.domain.ConsentRecord;

import java.util.List;
import java.util.UUID;

public interface ConsentRepository {
    ConsentRecord save(ConsentRecord consent);
    List<ConsentRecord> findActiveByPartyId(UUID partyId);
    List<ConsentRecord> findAllByPartyId(UUID partyId);
}
