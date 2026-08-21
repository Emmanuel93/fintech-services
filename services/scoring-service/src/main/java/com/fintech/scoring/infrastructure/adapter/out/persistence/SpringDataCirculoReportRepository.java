package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.domain.CirculoReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataCirculoReportRepository extends JpaRepository<CirculoReport, UUID> {
    Optional<CirculoReport> findFirstByProspectIdOrderByQueriedAtDesc(UUID prospectId);
}
