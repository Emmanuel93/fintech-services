package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.PartyVerificationRepository;
import com.fintech.creditportfolio.domain.PartyVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

interface SpringDataPartyVerificationRepository extends JpaRepository<PartyVerification, UUID> {
    Optional<PartyVerification> findFirstByPartyId(UUID partyId);
    Optional<PartyVerification> findFirstByProspectId(UUID prospectId);
}

@Component
class JpaPartyVerificationAdapter implements PartyVerificationRepository {

    private final SpringDataPartyVerificationRepository repo;

    JpaPartyVerificationAdapter(SpringDataPartyVerificationRepository repo) { this.repo = repo; }

    @Override
    public Optional<PartyVerification> findByPartyId(UUID personId) {
        // Primero por party, que es la llave más específica; si no, por prospecto. Los dos espacios
        // de UUID no se cruzan, así que no hay forma de que un id de uno case con una fila ajena.
        return repo.findFirstByPartyId(personId).or(() -> repo.findFirstByProspectId(personId));
    }

    @Override public Optional<PartyVerification> findByProspectId(UUID p) { return repo.findFirstByProspectId(p); }
    @Override public PartyVerification save(PartyVerification v)          { return repo.save(v); }
}
