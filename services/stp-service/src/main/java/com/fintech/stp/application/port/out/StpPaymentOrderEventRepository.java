package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.StpPaymentOrderEvent;

import java.util.List;
import java.util.UUID;

public interface StpPaymentOrderEventRepository {

    StpPaymentOrderEvent save(StpPaymentOrderEvent event);

    List<StpPaymentOrderEvent> findByStpPaymentOrderIdOrderByOccurredAt(UUID stpPaymentOrderId);
}
