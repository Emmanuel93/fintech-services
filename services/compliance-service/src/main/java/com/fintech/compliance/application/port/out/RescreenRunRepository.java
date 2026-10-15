package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.RescreenRun;

import java.util.List;
import java.util.UUID;

public interface RescreenRunRepository {
    RescreenRun save(RescreenRun run);
    List<RescreenRun> findRecent(int limit);
    java.util.Optional<RescreenRun> findById(UUID runId);
}
