package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.domain.PrequalificationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataPrequalificationSnapshotRepository extends JpaRepository<PrequalificationSnapshot, UUID> {
    Optional<PrequalificationSnapshot> findByProspectId(UUID prospectId);
}
