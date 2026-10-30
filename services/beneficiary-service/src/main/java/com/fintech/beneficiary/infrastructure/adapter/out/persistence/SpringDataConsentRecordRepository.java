package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.application.port.out.ConsentRecordRepository;
import com.fintech.beneficiary.domain.ConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataConsentRecordRepository
        extends JpaRepository<ConsentRecord, UUID>, ConsentRecordRepository {

    @Override
    List<ConsentRecord> findBySessionId(UUID sessionId);

    @Override
    List<ConsentRecord> findByPlacementIdOrderByAcceptedAtAsc(UUID placementId);
}
