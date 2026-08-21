package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.StpPaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaStpPaymentOrderRepository
        extends JpaRepository<StpPaymentOrder, UUID>, StpPaymentOrderRepository {

    @Override
    Optional<StpPaymentOrder> findByPaymentRequestId(UUID paymentRequestId);

    @Override
    boolean existsByPaymentRequestId(UUID paymentRequestId);

    @Override
    Optional<StpPaymentOrder> findByCompanyIdAndTrackingKey(UUID companyId, String trackingKey);

    @Override
    List<StpPaymentOrder> findByCompanyIdAndBusinessDate(UUID companyId, LocalDate businessDate);

    @Override
    @Query("""
            SELECT o FROM StpPaymentOrder o
            WHERE o.status IN ('SENT', 'ACCEPTED')
              AND (o.sentAt IS NULL OR o.sentAt < :sentBefore)
            ORDER BY o.companyId, o.businessDate
            """)
    List<StpPaymentOrder> findInFlightOlderThan(@Param("sentBefore") Instant sentBefore);

    @Override
    @Query("""
            SELECT o FROM StpPaymentOrder o
            WHERE o.status IN ('SENT', 'ACCEPTED')
              AND o.sentAt < :threshold
            """)
    List<StpPaymentOrder> findInFlightStuckSince(@Param("threshold") Instant threshold);
}
