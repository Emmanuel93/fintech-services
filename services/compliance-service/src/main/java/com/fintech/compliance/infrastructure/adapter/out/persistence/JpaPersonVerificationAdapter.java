package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.PersonVerificationRepository;
import com.fintech.compliance.domain.PersonVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

interface SpringDataPersonVerificationRepository extends JpaRepository<PersonVerification, UUID> {
    Optional<PersonVerification> findByProspectId(UUID prospectId);
    Optional<PersonVerification> findByPartyId(UUID partyId);
}

@Component
class JpaPersonVerificationAdapter implements PersonVerificationRepository {

    private final SpringDataPersonVerificationRepository repo;

    JpaPersonVerificationAdapter(SpringDataPersonVerificationRepository repo) { this.repo = repo; }

    @Override public Optional<PersonVerification> findByProspectId(UUID p) { return repo.findByProspectId(p); }
    @Override public Optional<PersonVerification> findByPartyId(UUID p)    { return repo.findByPartyId(p); }
    @Override public Optional<PersonVerification> findById(UUID id)        { return repo.findById(id); }
    @Override public PersonVerification save(PersonVerification v)         { return repo.save(v); }

    @Override public java.util.List<PersonVerification> findPage(int page, int size) {
        // Orden estable por fecha de creación: sin él, dos páginas consecutivas pueden repetir y
        // saltarse filas, y un re-cruce dejaría gente sin mirar sin que nada lo indicara.
        return repo.findAll(org.springframework.data.domain.PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("createdAt").ascending())).getContent();
    }
}
