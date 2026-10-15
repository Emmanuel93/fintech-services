package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.WatchlistEntryRepository;
import com.fintech.compliance.domain.WatchlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

interface SpringDataWatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {
    long countByVersionId(UUID versionId);
    @Transactional void deleteByVersionId(UUID versionId);
}

@Component
class JpaWatchlistEntryAdapter implements WatchlistEntryRepository {

    private final SpringDataWatchlistEntryRepository repo;

    JpaWatchlistEntryAdapter(SpringDataWatchlistEntryRepository repo) { this.repo = repo; }

    @Override public void saveAll(List<WatchlistEntry> batch) { repo.saveAll(batch); }
    @Override public long countByVersionId(UUID v)            { return repo.countByVersionId(v); }
    @Override public void deleteByVersionId(UUID v)           { repo.deleteByVersionId(v); }
}
