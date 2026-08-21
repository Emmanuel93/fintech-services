package com.fintech.scoring.application.port.out;

import com.fintech.scoring.domain.CirculoReport;

import java.util.Optional;
import java.util.UUID;

public interface CirculoReportRepository {
    CirculoReport save(CirculoReport report);
    Optional<CirculoReport> findByProspectId(UUID prospectId);
}
