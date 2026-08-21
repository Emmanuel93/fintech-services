package com.fintech.scoring.application.port.out;

import com.fintech.scoring.domain.PrequalificationSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface PrequalificationSnapshotRepository {
    PrequalificationSnapshot save(PrequalificationSnapshot snapshot);
    Optional<PrequalificationSnapshot> findByProspectId(UUID prospectId);
}
