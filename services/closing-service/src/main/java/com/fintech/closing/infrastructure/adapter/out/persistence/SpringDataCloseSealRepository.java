package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.domain.CloseSeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

interface SpringDataCloseSealRepository extends JpaRepository<CloseSeal, UUID> {
    Optional<CloseSeal> findByBusinessDateAndPhaseAndScopeKey(LocalDate d, String phase, String scopeKey);
}
