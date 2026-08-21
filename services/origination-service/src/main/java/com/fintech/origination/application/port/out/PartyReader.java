package com.fintech.origination.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface PartyReader {

    Optional<PartyData> findByPartyId(UUID partyId);

    record PartyData(UUID prospectId, String partyType) {}
}
