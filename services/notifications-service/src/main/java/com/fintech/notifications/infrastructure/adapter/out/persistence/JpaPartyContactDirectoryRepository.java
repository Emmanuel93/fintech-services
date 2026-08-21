package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.PartyContactDirectoryRepository;
import com.fintech.notifications.domain.PartyContactDirectory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaPartyContactDirectoryRepository
        extends JpaRepository<PartyContactDirectory, UUID>, PartyContactDirectoryRepository {
}
