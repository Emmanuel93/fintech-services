package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.CloseRunRepository;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
class JpaCloseRunAdapter implements CloseRunRepository {

    private final SpringDataCloseRunRepository jpa;

    JpaCloseRunAdapter(SpringDataCloseRunRepository jpa) { this.jpa = jpa; }

    @Override
    public Optional<CloseRun> find(LocalDate businessDate, ClosePhase phase, String scopeKey) {
        return jpa.findByBusinessDateAndPhaseAndScopeKey(businessDate, phase.name(), scopeKey);
    }

    @Override
    public CloseRun save(CloseRun run) { return jpa.save(run); }
}
