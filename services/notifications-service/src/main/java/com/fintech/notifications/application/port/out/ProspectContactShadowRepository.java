package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.ProspectContactShadow;

import java.util.Optional;
import java.util.UUID;

public interface ProspectContactShadowRepository {
    Optional<ProspectContactShadow> findById(UUID prospectId);
    ProspectContactShadow save(ProspectContactShadow shadow);
}
