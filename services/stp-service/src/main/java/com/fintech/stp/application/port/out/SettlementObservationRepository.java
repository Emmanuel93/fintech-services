package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.SettlementObservation;

import java.util.List;
import java.util.UUID;

public interface SettlementObservationRepository {

    /** SO-02: el poller relee lo mismo cada N minutos por diseño. */
    boolean existsByCompanyIdAndTrackingKeyAndObservedStatusAndObservedAtSource(
            UUID companyId, String trackingKey, String observedStatus, String observedAtSource);

    List<SettlementObservation> findByCompanyIdAndTrackingKeyOrderByObservedAtDesc(
            UUID companyId, String trackingKey);

    SettlementObservation save(SettlementObservation observation);
}
