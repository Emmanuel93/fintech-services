package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.domain.CloseRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

interface SpringDataCloseRunRepository extends JpaRepository<CloseRun, UUID> {
    Optional<CloseRun> findByBusinessDateAndPhaseAndScopeKey(LocalDate businessDate, String phase, String scopeKey);
}
