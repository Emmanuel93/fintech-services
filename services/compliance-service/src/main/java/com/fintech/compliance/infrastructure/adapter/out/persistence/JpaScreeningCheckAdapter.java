package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.ScreeningCheckRepository;
import com.fintech.compliance.domain.ScreeningCheck;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

interface SpringDataScreeningCheckRepository extends JpaRepository<ScreeningCheck, UUID> {
    List<ScreeningCheck> findByProspectIdOrderByCheckedAtDesc(UUID prospectId, PageRequest page);
    long countByProspectId(UUID prospectId);
}

@Component
class JpaScreeningCheckAdapter implements ScreeningCheckRepository {

    private final SpringDataScreeningCheckRepository repo;

    JpaScreeningCheckAdapter(SpringDataScreeningCheckRepository repo) { this.repo = repo; }

    @Override public ScreeningCheck save(ScreeningCheck check) { return repo.save(check); }

    @Override public List<ScreeningCheck> findByProspectId(UUID prospectId, int limit) {
        return repo.findByProspectIdOrderByCheckedAtDesc(prospectId, PageRequest.of(0, limit));
    }

    @Override public long countByProspectId(UUID prospectId) {
        return repo.countByProspectId(prospectId);
    }
}
