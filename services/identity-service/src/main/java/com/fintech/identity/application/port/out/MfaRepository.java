package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.MfaConfig;

import java.util.Optional;
import java.util.UUID;

public interface MfaRepository {

    Optional<MfaConfig> findByPartyId(UUID partyId);

    MfaConfig save(MfaConfig mfaConfig);
}
