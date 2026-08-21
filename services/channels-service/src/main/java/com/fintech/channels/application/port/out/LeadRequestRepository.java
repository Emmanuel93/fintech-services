package com.fintech.channels.application.port.out;

import com.fintech.channels.domain.LeadRequest;
import java.util.Optional;
import java.util.UUID;

public interface LeadRequestRepository {
    Optional<LeadRequest> findById(UUID leadId);
    LeadRequest save(LeadRequest lead);
}
