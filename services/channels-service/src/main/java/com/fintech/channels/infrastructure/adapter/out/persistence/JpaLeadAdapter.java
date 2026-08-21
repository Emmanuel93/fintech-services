package com.fintech.channels.infrastructure.adapter.out.persistence;

import com.fintech.channels.application.port.out.LeadRequestRepository;
import com.fintech.channels.domain.LeadRequest;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaLeadAdapter implements LeadRequestRepository {

    private final SpringDataLeadRequestRepository jpa;

    JpaLeadAdapter(SpringDataLeadRequestRepository jpa) {
        this.jpa = jpa;
    }

    @Override public Optional<LeadRequest> findById(UUID id) { return jpa.findById(id); }
    @Override public LeadRequest save(LeadRequest lead) { return jpa.save(lead); }
}
