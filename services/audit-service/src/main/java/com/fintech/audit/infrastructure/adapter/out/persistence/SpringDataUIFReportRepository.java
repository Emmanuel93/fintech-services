package com.fintech.audit.infrastructure.adapter.out.persistence;

import com.fintech.audit.domain.UIFReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataUIFReportRepository extends JpaRepository<UIFReport, UUID> {
    List<UIFReport> findByPartyIdOrderByCreatedAtDesc(UUID partyId);
}
