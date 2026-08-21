package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.ApplicationProspectLinkRepository;
import com.fintech.notifications.domain.ApplicationProspectLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaApplicationProspectLinkRepository
        extends JpaRepository<ApplicationProspectLink, UUID>, ApplicationProspectLinkRepository {
}
