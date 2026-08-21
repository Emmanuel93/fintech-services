package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.port.out.BureauReportRepository;
import com.fintech.collections.domain.BureauReport;
import com.fintech.collections.domain.BureauReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaBureauReportRepository
        extends JpaRepository<BureauReport, UUID>, BureauReportRepository {

    @Override
    List<BureauReport> findByStatusIn(List<BureauReportStatus> statuses);

    @Override
    boolean existsBySourceRecordId(UUID sourceRecordId);
}
