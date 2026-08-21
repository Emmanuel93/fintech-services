package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.application.port.out.PrequalificationSnapshotRepository;
import com.fintech.scoring.domain.PrequalificationSnapshot;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPrequalificationSnapshotAdapter implements PrequalificationSnapshotRepository {

    private final SpringDataPrequalificationSnapshotRepository jpa;

    JpaPrequalificationSnapshotAdapter(SpringDataPrequalificationSnapshotRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PrequalificationSnapshot save(PrequalificationSnapshot snapshot) {
        return jpa.save(snapshot);
    }

    @Override
    public Optional<PrequalificationSnapshot> findByProspectId(UUID prospectId) {
        return jpa.findByProspectId(prospectId);
    }
}
