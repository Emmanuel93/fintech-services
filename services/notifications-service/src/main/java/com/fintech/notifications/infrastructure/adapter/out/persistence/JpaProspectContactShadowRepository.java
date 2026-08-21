package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.ProspectContactShadowRepository;
import com.fintech.notifications.domain.ProspectContactShadow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaProspectContactShadowRepository
        extends JpaRepository<ProspectContactShadow, UUID>, ProspectContactShadowRepository {
}
