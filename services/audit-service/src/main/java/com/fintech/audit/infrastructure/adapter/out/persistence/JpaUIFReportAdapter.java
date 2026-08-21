package com.fintech.audit.infrastructure.adapter.out.persistence;

import com.fintech.audit.application.port.out.UIFReportRepository;
import com.fintech.audit.domain.UIFReport;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaUIFReportAdapter implements UIFReportRepository {

    private final SpringDataUIFReportRepository jpa;

    JpaUIFReportAdapter(SpringDataUIFReportRepository jpa) {
        this.jpa = jpa;
    }

    @Override public UIFReport save(UIFReport report) { return jpa.save(report); }
    @Override public Optional<UIFReport> findById(UUID id) { return jpa.findById(id); }

    @Override
    public List<UIFReport> findByPartyId(UUID partyId) {
        return jpa.findByPartyIdOrderByCreatedAtDesc(partyId);
    }
}
