package com.fintech.payments.application.port.out;

import com.fintech.payments.domain.PaymentOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository {
    Optional<PaymentOrder> findById(UUID paymentOrderId);
    Optional<PaymentOrder> findByExternalRef(String externalRef);
    List<PaymentOrder> findAllByCreditAccountIdOrderByCreatedAtDesc(UUID creditAccountId);
    boolean existsByExternalRef(String externalRef);
    PaymentOrder save(PaymentOrder order);
}
