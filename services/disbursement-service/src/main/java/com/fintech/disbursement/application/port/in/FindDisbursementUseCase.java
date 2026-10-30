package com.fintech.disbursement.application.port.in;

import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.domain.DisbursementEvent;
import com.fintech.disbursement.domain.DisbursementOrder;

import java.util.List;
import java.util.UUID;

public interface FindDisbursementUseCase {

    DisbursementOrder findById(UUID disbursementId);

    List<DisbursementEvent> timeline(UUID disbursementId);

    List<DisbursementOrder> findByCompany(UUID companyId, int page, int size);

    /** Tesorería: por crédito y/o por beneficiario o clave de rastreo, del más nuevo al más viejo. */
    DisbursementOrderRepository.OrderPage search(UUID creditAccountId, String q, int page, int size);
}
