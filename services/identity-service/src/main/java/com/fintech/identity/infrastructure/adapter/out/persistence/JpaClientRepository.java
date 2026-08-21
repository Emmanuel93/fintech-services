package com.fintech.identity.infrastructure.adapter.out.persistence;

import com.fintech.identity.application.port.out.ClientRepository;
import com.fintech.identity.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaClientRepository extends JpaRepository<Client, UUID>, ClientRepository {

    @Override
    Optional<Client> findByClientId(String clientId);
}
