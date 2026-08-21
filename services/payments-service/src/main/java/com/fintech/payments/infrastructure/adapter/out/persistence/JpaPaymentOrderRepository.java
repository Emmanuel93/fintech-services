package com.fintech.payments.infrastructure.adapter.out.persistence;

import com.fintech.payments.application.port.out.PaymentOrderRepository;
import com.fintech.payments.domain.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaPaymentOrderRepository
        extends JpaRepository<PaymentOrder, UUID>, PaymentOrderRepository {

    @Override
    Optional<PaymentOrder> findByExternalRef(String externalRef);

    @Override
    boolean existsByExternalRef(String externalRef);

    @Override
    List<PaymentOrder> findAllByCreditAccountIdOrderByCreatedAtDesc(UUID creditAccountId);
}
