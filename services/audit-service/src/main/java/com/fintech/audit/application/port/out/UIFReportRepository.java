package com.fintech.audit.application.port.out;

import com.fintech.audit.domain.UIFReport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UIFReportRepository {
    UIFReport save(UIFReport report);
    Optional<UIFReport> findById(UUID reportId);
    List<UIFReport> findByPartyId(UUID partyId);
}
