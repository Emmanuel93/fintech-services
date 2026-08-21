package com.fintech.stp.application.port.in;

import com.fintech.stp.domain.SettlementObservation;
import com.fintech.stp.domain.StpPaymentOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FindPaymentOrderUseCase {

    StpPaymentOrder findByPaymentRequestId(UUID paymentRequestId);

    List<StpPaymentOrder> findByCompanyAndBusinessDate(UUID companyId, LocalDate businessDate);

    List<SettlementObservation> findObservations(UUID companyId, String trackingKey);
}
