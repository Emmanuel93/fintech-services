package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.domain.Disposition;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaDispositionAdapter implements DispositionRepository {

    private final SpringDataDispositionRepository jpa;

    JpaDispositionAdapter(SpringDataDispositionRepository jpa) {
        this.jpa = jpa;
    }

    @Override public Disposition save(Disposition d) { return jpa.save(d); }
    @Override public Optional<Disposition> findById(UUID id) { return jpa.findById(id); }
    @Override public List<Disposition> findByCreditAccountId(UUID id) { return jpa.findByCreditAccountId(id); }

    @Override public List<Disposition> findByCreditAccountIdIn(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return jpa.findByCreditAccountIdIn(ids);
    }
}
