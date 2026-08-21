package com.fintech.stp.application.service;

import com.fintech.stp.application.port.in.FindPaymentOrderUseCase;
import com.fintech.stp.application.port.out.SettlementObservationRepository;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.SettlementObservation;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.domain.StpPaymentOrderNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentOrderQueryService implements FindPaymentOrderUseCase {

    private final StpPaymentOrderRepository orderRepository;
    private final SettlementObservationRepository observationRepository;

    public PaymentOrderQueryService(StpPaymentOrderRepository orderRepository,
                                     SettlementObservationRepository observationRepository) {
        this.orderRepository = orderRepository;
        this.observationRepository = observationRepository;
    }

    @Override
    public StpPaymentOrder findByPaymentRequestId(UUID paymentRequestId) {
        return orderRepository.findByPaymentRequestId(paymentRequestId)
                .orElseThrow(() -> new StpPaymentOrderNotFoundException(
                        "No existe orden para paymentRequestId=" + paymentRequestId));
    }

    @Override
    public List<StpPaymentOrder> findByCompanyAndBusinessDate(UUID companyId, LocalDate businessDate) {
        return orderRepository.findByCompanyIdAndBusinessDate(companyId, businessDate);
    }

    @Override
    public List<SettlementObservation> findObservations(UUID companyId, String trackingKey) {
        return observationRepository.findByCompanyIdAndTrackingKeyOrderByObservedAtDesc(companyId, trackingKey);
    }
}
