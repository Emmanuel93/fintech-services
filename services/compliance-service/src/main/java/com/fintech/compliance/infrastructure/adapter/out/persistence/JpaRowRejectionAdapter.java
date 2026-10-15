package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.RowRejectionRepository;
import com.fintech.compliance.domain.RowRejection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

interface SpringDataRowRejectionRepository extends JpaRepository<RowRejection, UUID> {
    List<RowRejection> findByVersionIdOrderBySourceLineAsc(UUID versionId, PageRequest page);
}

@Component
class JpaRowRejectionAdapter implements RowRejectionRepository {

    private final SpringDataRowRejectionRepository repo;

    JpaRowRejectionAdapter(SpringDataRowRejectionRepository repo) { this.repo = repo; }

    @Override public void saveAll(List<RowRejection> batch) { repo.saveAll(batch); }

    @Override public List<RowRejection> findByVersionId(UUID versionId, int limit) {
        return repo.findByVersionIdOrderBySourceLineAsc(versionId, PageRequest.of(0, limit));
    }
}
