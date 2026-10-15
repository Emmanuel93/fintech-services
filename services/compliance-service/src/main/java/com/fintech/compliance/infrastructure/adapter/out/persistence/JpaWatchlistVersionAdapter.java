package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.WatchlistVersionRepository;
import com.fintech.compliance.domain.WatchlistVersion;
import com.fintech.compliance.domain.WatchlistVersionStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class JpaWatchlistVersionAdapter implements WatchlistVersionRepository {

    private final SpringDataWatchlistVersionRepository repo;

    JpaWatchlistVersionAdapter(SpringDataWatchlistVersionRepository repo) { this.repo = repo; }

    @Override public Optional<WatchlistVersion> findByFileHash(String h) { return repo.findByFileHash(h); }

    @Override public Optional<WatchlistVersion> findBySourceCodeAndStatus(String code, WatchlistVersionStatus st) {
        return repo.findBySourceCodeAndStatus(code, st);
    }

    @Override public Optional<WatchlistVersion> findLastFinished(String code) {
        return repo.findFinished(code).stream().findFirst();
    }

    @Override public java.util.Optional<WatchlistVersion> findLastActivatedAfter(java.time.Instant since) {
        return repo.findFirstByStatusAndActivatedAtAfterOrderByActivatedAtDesc(
                WatchlistVersionStatus.ACTIVE, since);
    }

    @Override public WatchlistVersion save(WatchlistVersion v) { return repo.save(v); }
}
