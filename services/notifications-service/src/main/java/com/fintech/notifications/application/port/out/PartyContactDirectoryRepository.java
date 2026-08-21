package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.PartyContactDirectory;

import java.util.Optional;
import java.util.UUID;

public interface PartyContactDirectoryRepository {
    Optional<PartyContactDirectory> findById(UUID partyId);
    PartyContactDirectory save(PartyContactDirectory directory);
}
