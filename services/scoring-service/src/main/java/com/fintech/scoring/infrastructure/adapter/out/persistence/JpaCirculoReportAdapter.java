package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.domain.CirculoReport;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCirculoReportAdapter implements CirculoReportRepository {

    private final SpringDataCirculoReportRepository jpa;

    JpaCirculoReportAdapter(SpringDataCirculoReportRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public CirculoReport save(CirculoReport report) {
        return jpa.save(report);
    }

    @Override
    public Optional<CirculoReport> findByProspectId(UUID prospectId) {
        return jpa.findFirstByProspectIdOrderByQueriedAtDesc(prospectId);
    }
}
