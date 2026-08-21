package com.fintech.scoring.application.port.out;

import com.fintech.scoring.domain.BureauPrefetch;

import java.util.Optional;
import java.util.UUID;

public interface BureauPrefetchRepository {
    BureauPrefetch save(BureauPrefetch prefetch);
    Optional<BureauPrefetch> findById(UUID prefetchId);
    boolean existsActiveByProspectId(UUID prospectId);
}
