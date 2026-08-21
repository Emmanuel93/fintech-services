package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.ClientIpEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientIpRepository {
    List<ClientIpEntry> findByClientId(UUID clientId);
    ClientIpEntry save(ClientIpEntry entry);
    Optional<ClientIpEntry> findById(UUID id);
    void deleteById(UUID id);
}
