package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.StpPaymentOrderEventRepository;
import com.fintech.stp.domain.StpPaymentOrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaStpPaymentOrderEventRepository
        extends JpaRepository<StpPaymentOrderEvent, UUID>, StpPaymentOrderEventRepository {

    @Override
    List<StpPaymentOrderEvent> findByStpPaymentOrderIdOrderByOccurredAt(UUID stpPaymentOrderId);
}
