package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;

import java.time.LocalDate;
import java.util.Optional;

public interface CloseRunRepository {
    Optional<CloseRun> find(LocalDate businessDate, ClosePhase phase, String scopeKey);
    CloseRun save(CloseRun run);
}
