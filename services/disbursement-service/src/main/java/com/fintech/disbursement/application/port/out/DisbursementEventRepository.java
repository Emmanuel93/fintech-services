package com.fintech.disbursement.application.port.out;

import com.fintech.disbursement.domain.DisbursementEvent;

import java.util.List;
import java.util.UUID;

public interface DisbursementEventRepository {

    DisbursementEvent save(DisbursementEvent event);

    List<DisbursementEvent> findByDisbursementId(UUID disbursementId);
}
