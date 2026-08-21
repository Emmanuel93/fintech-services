package com.fintech.audit.application.service;

import com.fintech.audit.application.port.out.UIFReportRepository;
import com.fintech.audit.domain.UIFReport;
import com.fintech.audit.domain.UIFReportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class UIFReportService {

    private static final Logger log = LoggerFactory.getLogger(UIFReportService.class);

    private final UIFReportRepository repository;

    public UIFReportService(UIFReportRepository repository) {
        this.repository = repository;
    }

    public UIFReport create(UUID partyId, UIFReportType reportType,
                            String triggerEventType, String triggerAggregateId,
                            String notes) {
        UIFReport report = UIFReport.create(partyId, reportType, triggerEventType, triggerAggregateId, notes);
        repository.save(report);
        log.warn("UIF report created partyId={} type={} trigger={}", partyId, reportType, triggerEventType);
        return report;
    }

    @Transactional(readOnly = true)
    public List<UIFReport> findByPartyId(UUID partyId) {
        return repository.findByPartyId(partyId);
    }
}
