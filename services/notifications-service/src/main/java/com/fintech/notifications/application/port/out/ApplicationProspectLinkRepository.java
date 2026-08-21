package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.ApplicationProspectLink;

import java.util.Optional;
import java.util.UUID;

public interface ApplicationProspectLinkRepository {
    Optional<ApplicationProspectLink> findById(UUID applicationId);
    ApplicationProspectLink save(ApplicationProspectLink link);
}
