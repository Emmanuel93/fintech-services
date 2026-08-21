package com.fintech.identity.infrastructure.adapter.out.persistence;

import com.fintech.identity.application.port.out.ClientIpRepository;
import com.fintech.identity.domain.ClientIpEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaClientIpRepository extends JpaRepository<ClientIpEntry, UUID>, ClientIpRepository {

    @Override
    List<ClientIpEntry> findByClientId(UUID clientId);
}
