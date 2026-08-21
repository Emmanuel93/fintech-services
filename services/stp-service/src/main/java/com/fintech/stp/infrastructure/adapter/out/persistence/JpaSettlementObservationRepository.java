package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.SettlementObservationRepository;
import com.fintech.stp.domain.SettlementObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaSettlementObservationRepository
        extends JpaRepository<SettlementObservation, UUID>, SettlementObservationRepository {

    @Override
    boolean existsByCompanyIdAndTrackingKeyAndObservedStatusAndObservedAtSource(
            UUID companyId, String trackingKey, String observedStatus, String observedAtSource);

    @Override
    List<SettlementObservation> findByCompanyIdAndTrackingKeyOrderByObservedAtDesc(
            UUID companyId, String trackingKey);
}
