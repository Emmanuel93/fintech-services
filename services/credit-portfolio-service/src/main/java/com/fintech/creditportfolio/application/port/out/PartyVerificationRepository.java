package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.PartyVerification;

import java.util.Optional;
import java.util.UUID;

public interface PartyVerificationRepository {
    Optional<PartyVerification> findByPartyId(UUID partyId);
    PartyVerification save(PartyVerification verification);
}
