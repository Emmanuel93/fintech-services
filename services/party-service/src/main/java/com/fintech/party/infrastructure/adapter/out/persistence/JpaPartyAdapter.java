package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyStatus;
import com.fintech.party.domain.PartyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaPartyAdapter implements PartyRepository {

    private final SpringDataPartyRepository repository;

    public JpaPartyAdapter(SpringDataPartyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Party save(Party party) {
        return repository.save(party);
    }

    @Override
    public Optional<Party> findById(UUID partyId) {
        return repository.findById(partyId);
    }

    @Override
    public Optional<Party> findByProspectId(UUID prospectId) {
        return repository.findByProspectId(prospectId);
    }

    @Override
    public boolean existsByProspectId(UUID prospectId) {
        return repository.existsByProspectId(prospectId);
    }

    @Override
    public Page<Party> search(String q, PartyType type, PartyStatus status, UUID executiveId, Pageable pageable) {
        // El comodín y las minúsculas se arman aquí, no en el controlador ni en
        // la query: así la consulta compara contra la columna en minúsculas y
        // puede apoyarse en el índice GIN trigram (ver migración 008).
        String needle = (q == null || q.isBlank()) ? null : "%" + q.toLowerCase() + "%";
        return repository.search(needle, type, status, executiveId, pageable);
    }

    @Override
    public List<Party> findByPartyIdIn(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repository.findByPartyIdIn(ids);
    }

    @Override
    public List<Party> findByProspectIdIn(Collection<UUID> prospectIds) {
        if (prospectIds == null || prospectIds.isEmpty()) {
            return List.of();
        }
        return repository.findByProspectIdIn(prospectIds);
    }
}
