package com.fintech.disbursement.application.port.in;

import com.fintech.disbursement.domain.DisbursementEvent;
import com.fintech.disbursement.domain.DisbursementOrder;

import java.util.List;
import java.util.UUID;

public interface FindDisbursementUseCase {

    DisbursementOrder findById(UUID disbursementId);

    List<DisbursementEvent> timeline(UUID disbursementId);

    List<DisbursementOrder> findByCompany(UUID companyId, int page, int size);
}
