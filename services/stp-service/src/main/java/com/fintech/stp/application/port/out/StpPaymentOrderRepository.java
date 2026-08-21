package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.StpPaymentOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StpPaymentOrderRepository {

    Optional<StpPaymentOrder> findById(UUID stpPaymentOrderId);

    Optional<StpPaymentOrder> findByPaymentRequestId(UUID paymentRequestId);

    /** Guard de idempotencia de entrada. */
    boolean existsByPaymentRequestId(UUID paymentRequestId);

    Optional<StpPaymentOrder> findByCompanyIdAndTrackingKey(UUID companyId, String trackingKey);

    List<StpPaymentOrder> findByCompanyIdAndBusinessDate(UUID companyId, LocalDate businessDate);

    /** Lo que el poller tiene que consultar: SENT o ACCEPTED con gracia cumplida. */
    List<StpPaymentOrder> findInFlightOlderThan(Instant sentBefore);

    /** SO-06: en vuelo desde hace demasiado. Se alerta, no se marca liquidada. */
    List<StpPaymentOrder> findInFlightStuckSince(Instant threshold);

    StpPaymentOrder save(StpPaymentOrder order);
}
