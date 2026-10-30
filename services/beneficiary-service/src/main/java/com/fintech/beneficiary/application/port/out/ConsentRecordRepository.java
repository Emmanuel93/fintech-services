package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.ConsentRecord;

import java.util.List;
import java.util.UUID;

public interface ConsentRecordRepository {

    ConsentRecord save(ConsentRecord record);

    List<ConsentRecord> findBySessionId(UUID sessionId);

    List<ConsentRecord> findByPlacementIdOrderByAcceptedAtAsc(UUID placementId);
}
