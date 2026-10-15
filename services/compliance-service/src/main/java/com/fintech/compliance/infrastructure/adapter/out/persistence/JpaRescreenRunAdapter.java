package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.RescreenRunRepository;
import com.fintech.compliance.domain.RescreenRun;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataRescreenRunRepository extends JpaRepository<RescreenRun, UUID> { }

@Component
class JpaRescreenRunAdapter implements RescreenRunRepository {

    private final SpringDataRescreenRunRepository repo;

    JpaRescreenRunAdapter(SpringDataRescreenRunRepository repo) { this.repo = repo; }

    @Override public RescreenRun save(RescreenRun run) { return repo.save(run); }
    @Override public Optional<RescreenRun> findById(UUID runId) { return repo.findById(runId); }

    @Override public List<RescreenRun> findRecent(int limit) {
        return repo.findAll(PageRequest.of(0, limit, Sort.by("startedAt").descending())).getContent();
    }
}
