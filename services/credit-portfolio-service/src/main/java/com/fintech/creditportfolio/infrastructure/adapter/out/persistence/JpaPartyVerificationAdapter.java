package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.PartyVerificationRepository;
import com.fintech.creditportfolio.domain.PartyVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

interface SpringDataPartyVerificationRepository extends JpaRepository<PartyVerification, UUID> { }

@Component
class JpaPartyVerificationAdapter implements PartyVerificationRepository {

    private final SpringDataPartyVerificationRepository repo;

    JpaPartyVerificationAdapter(SpringDataPartyVerificationRepository repo) { this.repo = repo; }

    @Override public Optional<PartyVerification> findByPartyId(UUID p) { return repo.findById(p); }
    @Override public PartyVerification save(PartyVerification v)       { return repo.save(v); }
}
