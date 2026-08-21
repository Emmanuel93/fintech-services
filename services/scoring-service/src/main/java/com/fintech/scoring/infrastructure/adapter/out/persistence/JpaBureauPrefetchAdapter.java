package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.application.port.out.BureauPrefetchRepository;
import com.fintech.scoring.domain.BureauPrefetch;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaBureauPrefetchAdapter implements BureauPrefetchRepository {

    private final SpringDataBureauPrefetchRepository jpa;

    JpaBureauPrefetchAdapter(SpringDataBureauPrefetchRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public BureauPrefetch save(BureauPrefetch prefetch) {
        return jpa.save(prefetch);
    }

    @Override
    public Optional<BureauPrefetch> findById(UUID prefetchId) {
        return jpa.findById(prefetchId);
    }

    @Override
    public boolean existsActiveByProspectId(UUID prospectId) {
        return jpa.existsActiveByProspectId(prospectId);
    }
}
