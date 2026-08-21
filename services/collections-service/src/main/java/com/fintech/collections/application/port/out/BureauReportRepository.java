package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.BureauReport;
import com.fintech.collections.domain.BureauReportStatus;
import java.util.List;
import java.util.UUID;

public interface BureauReportRepository {
    List<BureauReport> findByStatusIn(List<BureauReportStatus> statuses);
    boolean existsBySourceRecordId(UUID sourceRecordId);
    BureauReport save(BureauReport report);
}
